(ns org.clojars.roklenarcic.indexed-db.pathom
  (:require [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-server-remote]
            [com.fulcrologic.rad.authorization :as auth]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.form :as form]
            [com.fulcrologic.rad.middleware.save-middleware :as r.s.middleware]
            [com.fulcrologic.rad.resolvers :as res]
            [com.fulcrologic.guardrails.core :refer [>defn ? =>]]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as main]
            [org.clojars.roklenarcic.indexed-db.pathom-common :as common]
            [taoensso.encore :as enc]
            [taoensso.timbre :as log]
            [promesa.core :as promesa]
            [cljs.spec.alpha :as s]))

(def wrap-save (partial common/reify-middleware common/save-form!))

(def wrap-delete (partial common/reify-middleware common/delete-entity!))

(def form-resolvers
  [{::pc/sym    `form/delete-entity
    ::pc/mutate (fn [env params]
                  (if-let [delete-middleware (::form/delete-middleware env)]
                    (let [delete-env (assoc env ::form/params params)]
                      (delete-middleware delete-env))
                    (throw (ex-info "form/pathom-plugin in not installed on Pathom parser." {}))))}
   {::pc/mutate common/save-form*
    ::pc/sym `form/save-form
    ::pc/params #{::form/id ::form/master-pk ::form/delta}}
   {::pc/mutate common/save-form*
    ::pc/sym `form/save-as-form
    ::pc/params #{::form/id ::form/master-pk ::form/delta}}])

(>defn ->id-resolver
  "Generates a resolver from `id-attribute` to the `output-attributes`."
  [{::attr/keys [qualified-key schema] :keys [::main/wrap-resolve] :as id-attribute}
   output-attributes]
  [::attr/attribute ::attr/attributes => ::pc/resolver]
  (let [outputs     (attr/attributes->eql output-attributes)
        resolve-sym (symbol (str (symbol qualified-key) "-resolver"))
        with-resolve-sym (fn [r] (fn [env input] (r (assoc env ::pc/sym resolve-sym) input)))]
    (log/debug "Building ID resolver for" qualified-key "outputs" outputs)
    {::pc/sym resolve-sym
     ::pc/input #{qualified-key}
     ::pc/output outputs
     ::pc/batch? true
     ::pc/resolve (cond-> (fn [env input]
                            (log/debug "In resolver:" qualified-key "inputs:" input)
                            (promesa/let [tx (main/env->tx env schema)
                                          results (common/entity-query tx input qualified-key)]
                              (auth/redact env results)))
                    wrap-resolve (wrap-resolve)
                    :always (with-resolve-sym))}))

(>defn generate-resolvers
  "Generates resolvers for ID attributes to their related attributes"
  [attributes]
  [::attr/attributes => some?]
  (let [key->attribute (attr/attribute-map attributes)
        entity-id->attributes (group-by ::k (mapcat (fn [attribute]
                                                      (map
                                                        (fn [id-key] (assoc attribute ::k id-key))
                                                        (get attribute ::attr/identities)))
                                                    attributes))
        entity-resolvers      (reduce-kv
                                (fn [result k v]
                                  (enc/if-let [attr (key->attribute k)
                                               resolver (->id-resolver attr v)]
                                              (conj result resolver)
                                              (do
                                                (log/error "Internal error generating resolver for ID key" k)
                                                result)))
                                []
                                entity-id->attributes)]
    entity-resolvers))

(>defn pathom-plugin
  "A pathom plugin that takes a coll of schema confs and adds the necessary connections for schemas to env.
  See fulcro-rad-indexed-db for helper to construct these schema maps.

  It will also add resolvers for form mutations, because those are not supported for CLJS by fulcro-rad
  out of the box, and it will add resolvers for ID attributes within schemas. It will also add
  resolvers from the schemas' attributes that are specified on attribute with ::pc/resolve key"
  [all-attributes schema-confs extra-resolvers]
  [::attr/attributes ::main/schema-confs (s/coll-of map?) => map?]
  (let [schema-names (set (map ::main/schema-name schema-confs))
        filtered-attrs (filter (comp schema-names ::attr/schema) all-attributes)
        connections (common/connections-map schema-confs)]
    {::p/wrap-parser (fn env-wrap-wrap-parser [parser]
                       (fn env-wrap-wrap-internal [env tx]
                         (-> env
                             (assoc ::main/connections connections)
                             (parser tx))))
     ::pc/register (concat form-resolvers
                           (generate-resolvers filtered-attrs)
                           (res/generate-resolvers filtered-attrs)
                           extra-resolvers)}))

(>defn move-resolver
  "Moves Pathom Connect Resolver to the NS of the given object with NS.

  This is useful because to operate mutations as local+remote in CLJS you'll need defmutation twice in same *ns*,
  which would overload var name. So you'll want one mutation which a normal
  name and one pc/defmutation in another namespace."
  [with-ns-obj resolver]
  [some? map? => map?]
  (update resolver ::pc/sym #(symbol (namespace with-ns-obj) (name %))))

;;;; REMOTE section

(def query-params-to-env-plugin
  "Adds top-level load params to env, so nested parsing layers can see them."
  {::p/wrap-parser
   (fn [parser]
     (fn [env tx]
       (let [children (-> tx eql/query->ast :children)
             query-params (reduce
                            (fn [qps {:keys [type params] :as x}]
                              (cond-> qps
                                (and (not= :call type) (seq params)) (merge params)))
                            {}
                            children)
             env (assoc env :query-params query-params)]
         (parser env tx))))})

(defn process-error
  "If there were any exceptions in the parser that cause complete failure we
  respond with a well-known message that the client can handle."
  [env err]
  (let [msg  err
        data (or (ex-data err) {})]
    (log/error err "Parser Error:" msg data)
    {::errors {:message msg
               :data    data}}))

(>defn parser [all-attributes extra-resolvers schema-confs]
  [::attr/attributes (s/coll-of map?) ::main/schema-confs => some?]
  (p/async-parser
    {::p/mutate pc/mutate-async
     ::p/env {::p/reader [p/map-reader pc/async-reader2 pc/index-reader
                          pc/open-ident-reader p/env-placeholder-reader]
              ::p/placeholder-prefixes #{">"}
              ::pc/mutation-join-globals [:tempids]}
     ::p/plugins [(pc/connect-plugin)
                  ;; Install form middleware
                  (form/pathom-plugin (r.s.middleware/wrap-rewrite-values (wrap-save)) (wrap-delete))
                  (pathom-plugin all-attributes schema-confs extra-resolvers)
                  (attr/pathom-plugin all-attributes)       ; Other plugins need the list of attributes. This adds it to env.
                  (p/env-plugin {::p/process-error process-error})
                  ;; TODO: Do we need this, and if so, we need to pass the attribute map
                  ;(p/post-process-parser-plugin add-empty-vectors)
                  (p/post-process-parser-plugin p/elide-special-outputs)
                  query-params-to-env-plugin
                  p/error-handler-plugin]}))

(>defn remote [all-attributes extra-resolvers schema-confs]
  [::attr/attributes (s/coll-of map?) ::main/schema-confs => some?]
  (let [p (parser all-attributes extra-resolvers schema-confs)]
    (mock-server-remote/mock-http-server
      {:parser #(p {} %)})))

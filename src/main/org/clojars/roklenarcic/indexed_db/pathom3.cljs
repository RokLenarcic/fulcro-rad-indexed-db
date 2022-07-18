(ns org.clojars.roklenarcic.indexed-db.pathom3
  (:require [cljs.spec.alpha :as s]
            [com.fulcrologic.guardrails.core :refer [>defn ? =>]]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.form :as form]
            [com.fulcrologic.rad.resolvers :as res]
            [com.fulcrologic.rad.authorization :as auth]
            [com.fulcrologic.fulcro.networking.mock-server-remote :as mock-server-remote]
            [org.clojars.roklenarcic.indexed-db.pathom-common :as common]
            [com.wsscode.pathom3.connect.indexes :as pci]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.wsscode.pathom3.connect.runner :as pcr]
            [com.wsscode.pathom3.error :as p.error]
            [com.wsscode.pathom3.interface.async.eql :as p.a.eql]
            [com.wsscode.pathom3.plugin :as p.plugin]
            [edn-query-language.core :as eql]
            [taoensso.timbre :as log]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as main]
            [promesa.core :as promesa]))

(letfn [(has-cause? [err desired-cause] (boolean
                                          (some
                                            (fn [{::p.error/keys [cause]}] (= cause desired-cause))
                                            (some->> err ::p.error/node-error-details (vals)))))
        (missing? [err] (has-cause? err ::p.error/attribute-missing))
        (unreachable? [err] (= (::p.error/cause err) ::p.error/attribute-unreachable))
        (exception? [err] (has-cause? err ::p.error/node-exception))
        (node-exception [err] (some
                                (fn [{::p.error/keys [exception]}] exception)
                                (some->> err ::p.error/node-error-details (vals))))]
  (p.plugin/defplugin attribute-error-plugin
    {::p.error/wrap-attribute-error
     (fn [attribute-error]
       (fn [response attribute]
         (when-let [err (attribute-error response attribute)]
           (cond
             (missing? err) nil
             (unreachable? err) (log/errorf "EQL query for %s cannot be resolved. Is it spelled correctly? Pathom error: %s" attribute err)
             (exception? err) (log/error (node-exception err) "Resolver threw an exception while resolving" attribute)
             :else nil))))}))

(def form-resolvers
  [(pco/mutation
     `form/delete-entity
     {::pco/output [:tempids]}
     (fn [env params]
       (if-let [delete-middleware (::form/delete-middleware env)]
         (let [delete-env (assoc env ::form/params params)]
           (delete-middleware delete-env)
           {:tempids {}})
         (throw (ex-info "form/pathom-plugin in not installed on Pathom parser." {})))))
   (pco/mutation
     `form/save-form
     {::pco/params [::form/id ::form/master-pk ::form/delta]
      ::pco/output [:tempids]}
     common/save-form*)
   (pco/mutation
     `form/save-as-form
     {::pco/params [::form/id ::form/master-pk ::form/delta]
      ::pco/output [:tempids]}
     common/save-form*)])

(>defn ->id-resolver
  "Generates a resolver from `id-attribute` to the `output-attributes`."
  [{::attr/keys [qualified-key schema] :keys [::main/wrap-resolve] :as id-attribute}
   output-attributes]
  [::attr/attribute ::attr/attributes => ::pco/resolver]
  (let [outputs     (attr/attributes->eql output-attributes)
        resolve-sym (symbol (str (symbol qualified-key) "-resolver"))]
    (log/debug "Building ID resolver for" qualified-key "outputs" outputs)
    (pco/resolver
      resolve-sym
      {::pco/input [qualified-key]
       ::pco/output outputs
       ::pco/batch? true}
      (cond-> (fn [env input]
                (log/debug "In resolver:" qualified-key "inputs:" input)
                (promesa/let [tx (main/env->tx env schema)
                              results (common/entity-query tx input qualified-key)]
                  (auth/redact env results)))
        wrap-resolve (wrap-resolve)))))

(>defn generate-resolvers
  "Generates resolvers for ID attributes to their related attributes"
  [attributes]
  [::attr/attributes => some?]
  (common/generate-resolvers attributes ->id-resolver))

(>defn move-resolver
  "Moves Pathom Connect Resolver to the NS of the given object with NS.

  This is useful because to operate mutations as local+remote in CLJS you'll need defmutation twice in same *ns*,
  which would overload var name. So you'll want one mutation which a normal
  name and one pc/defmutation in another namespace."
  [with-ns-obj resolver]
  [some? map? => map?]
  (update resolver ::pco/op-name #(symbol (namespace with-ns-obj) (name %))))

;; Install form middleware
(def form-env-middleware (form/wrap-env common/wrap-save common/wrap-delete))

(s/def ::log-requests? boolean?)
(s/def ::log-responses? boolean?)
(s/def ::extra-plugins (s/coll-of map?))
(s/def ::extra-resolvers (s/coll-of map?))

(s/def ::config
  (s/keys :opt-un [::log-requests?
                   ::log-responses?
                   ::extra-resolvers
                   ::extra-plugins
                   ::env-middleware]))

(letfn [(wrap-mutate-exceptions [mutate]
          (fn [env ast]
            (try
              (mutate env ast)
              (catch js/Error e
                (log/errorf e "Mutation %s failed." (:key ast))
                ;; FIXME: Need a bit more work on returning errors that are handled globally.
                ;; Probably should just propagate exceptions out, so the client sees a server error
                ;; Pathom 2 compatible message so UI can detect the problem
                {:com.wsscode.pathom.core/errors [{:message (ex-message e)
                                                   :data    (ex-data e)}]}))))
        (combined-query-params [ast]
          (let [children     (:children ast)
                query-params (reduce
                               (fn [qps {:keys [type params] :as x}]
                                 (cond-> qps
                                   (and (not= :call type) (seq params)) (merge params)))
                               {}
                               children)]
            query-params))]
  (p.plugin/defplugin rewrite-mutation-exceptions {::pcr/wrap-mutate wrap-mutate-exceptions})
  (>defn processor
    "Create a new EQL processor.

    It will also add resolvers for form mutations, because those are not supported for CLJS by fulcro-rad
    out of the box, and it will add resolvers for ID attributes within schemas. It will also add
    resolvers from the schemas' attributes that are specified on attribute with ::pco/resolve key

    It will add :org.clojars.roklenarcic.fulcro-rad-indexed-db/connections to env

     The config options go under :org.clojars.roklenarcic.indexed-db.pathom3/config, and include:
     - log-requests?
     - log-responses?
     - extra-resolvers (list of extra resolvers to include)
     - env-middleware (fn [env] env)
     - extra-plugins list of plugins"
    [all-attributes
     schema-confs
     {:keys [log-requests?
             log-responses?
             extra-resolvers
             env-middleware
             extra-plugins] :as config}]
    [::attr/attributes ::main/schema-confs ::config => some?]
    (let [schema-names (set (map ::main/schema-name schema-confs))
          filtered-attrs (filter (comp schema-names ::attr/schema) all-attributes)
          base-env (-> {:com.wsscode.pathom3.format.eql/map-select-include #{:tempids}
                        ::main/connections (common/connections-map schema-confs)}
                       ((attr/wrap-env filtered-attrs))
                       form-env-middleware
                       (p.plugin/register (or extra-plugins []))
                       (p.plugin/register-plugin attribute-error-plugin)
                       (p.plugin/register-plugin rewrite-mutation-exceptions)
                       ;(p.plugin/register-plugin log-resolver-error)
                       (pci/register (concat form-resolvers
                                             (generate-resolvers filtered-attrs)
                                             (res/generate-resolvers filtered-attrs)
                                             extra-resolvers))
                       (assoc :config config))
          process  (p.a.eql/boundary-interface base-env)
          env-middleware (or env-middleware identity)]
      (fn [env tx]
        (when log-requests?
          (log/info "REQ:" {:env env :tx tx}))
        (let [ast      (eql/query->ast tx)
              env      (assoc
                         (env-middleware env)
                         ;; legacy param support
                         :query-params (combined-query-params ast))
              response (process env {:pathom/ast           ast
                                     :pathom/lenient-mode? true})]
          (when log-responses? (promesa/then response (fn [r] (log/info "RESP:" r))))
          response)))))

(>defn remote
  "Create a Fulcro remote, see parser docs for options"
  [all-attributes schema-confs extra-config]
  [::attr/attributes ::main/schema-confs (s/keys :req [::config])  => some?]
  (let [p (processor all-attributes schema-confs extra-config)]
    (mock-server-remote/mock-http-server {:parser #(p {} %)})))

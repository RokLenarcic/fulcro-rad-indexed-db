(ns org.clojars.roklenarcic.indexed-db.pathom-common
  (:require [clojure.spec.alpha :as s]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.guardrails.core :refer [>defn ? =>]]
            [com.fulcrologic.rad.form :as form]
            [edn-query-language.core :as eql]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as main]
            [org.clojars.roklenarcic.indexed-db.delta :as delta]
            [org.clojars.roklenarcic.indexed-db.store :as store]
            [taoensso.encore :as enc]
            [promesa.core :as p]
            [taoensso.timbre :as log]))

(>defn connections-map
  "Add connections (promises) to schema conf maps, and index them by schema name"
  [schemas]
  [::main/schema-confs => (s/map-of ::main/schema-name ::main/schema-conf)]
  (into {} (map (fn [{::main/keys [schema-name] :as sch}]
                  (->> (store/connect (str "fulcro-" (name schema-name)) true)
                       (assoc sch ::main/connection)
                       (vector schema-name))))
        schemas))

(defn deep-merge
  "Merges nested maps without overwriting existing keys."
  [& xs]
  (if (every? map? xs)
    (apply merge-with deep-merge xs)
    (last xs)))

(defn save-delta!
  [env delta]
  (let [schema-deltas (delta/schemas-for-delta env delta)]
    (-> (map (fn [[schema-name delta]]
               (log/debugf "Saving form for schema %s, delta %s" schema-name delta)
               (p/then (main/env->tx env schema-name)
                       #(delta/save-delta % delta)))
             schema-deltas)
        p/all
        (p/then #(hash-map :tempids (apply merge %))))))

(defn save-form! [{::form/keys [params] :as pathom-env}]
  (let [{::form/keys [delta]} params
        schema-deltas (delta/schemas-for-delta pathom-env delta)]
    (-> (map (fn [[schema-name delta]]
               (log/debugf "Saving form for schema %s, delta %s" schema-name delta)
               (p/then (main/env->tx pathom-env schema-name)
                       #(delta/save-delta % delta)))
             schema-deltas)
        p/all
        (p/then #(hash-map :tempids (apply merge %))))))

(defn delete-entity! [{::form/keys [params] :as pathom-env}]
  (log/debugf "Deleting entities %s" params)
  (enc/if-let [pk (ffirst params)
               id (get params pk)
               {::attr/keys [schema]} (get (::attr/key->attribute pathom-env) pk)]
    (p/then (main/env->tx pathom-env schema)
            #(main/delete-entity % [pk id]))))

(defn reify-middleware
  "Create a 2 arity middleware with an operation and merge"
  ([operation]
   (fn [pathom-env]
     (let [op-result (operation pathom-env)]
       op-result)))
  ([operation handler]
   (fn [pathom-env]
     (let [op-result    (operation pathom-env)]
       (p/then op-result (fn [res] (deep-merge res (handler pathom-env))))))))

(defn idents->value
  "reference is an ident or a vector of idents, or a scalar (in which case not a reference). Does not do any database
  reading, just changes [table id] to {table id}"
  [reference]
  (cond
    (eql/ident? reference) (let [[table id] reference] {table id})
    (vector? reference) (mapv idents->value reference)
    :else reference))

(>defn entity-query
  "Performs the query of the Key Value database. Uses the id-attribute that needs to be resolved and the input to the
  resolver which will contain the id/s that need to be queried for"
  [tx input id-key]
  [store/tx? any? keyword? => any?]
  (let [one? (not (sequential? input))
        ids (keep #(% id-key) (if one? [input] input))
        entities (reduce (fn [pacc id]
                           (p/let [acc pacc entity (main/get-entity tx [id-key id])]
                             (conj acc (enc/map-vals idents->value entity))))
                         (p/resolved [])
                         ids)]
    (p/then entities (fn [x] (if one? (first x) x)))))

(defn save-form*
  "Internal implementation of form save. Can be used in your own mutations to accomplish writes through
   the save middleware.

   params MUST contain:

   * `::form/delta` - The data to save. Map keyed by ident whose values are maps with `:before` and `:after` values.
   * `::form/id` - The actual ID of the entity being changed.
   * `::form/master-pk` - The keyword representing the form's ID in your RAD model's attributes.

   Returns:

   {:tempid {} ; tempid remaps
    master-pk id} ; the k/id of the entity saved. The id here will be remapped already if it was a tempid.
   "
  [env params]
  (let [save-middleware (::form/save-middleware env)
        save-env        (assoc env ::form/params params)
        result          (if save-middleware
                          (save-middleware save-env)
                          (throw (ex-info "form/pathom-plugin is not installed on the parser." {})))
        {::form/keys [id master-pk]} params]
    (p/then result (fn [{:keys [tempids] :as r}] (assoc r master-pk (get tempids id id))))))

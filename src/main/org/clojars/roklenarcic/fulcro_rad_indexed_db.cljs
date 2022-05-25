(ns org.clojars.roklenarcic.fulcro-rad-indexed-db
  (:require [clojure.spec.alpha :as s]
            [org.clojars.roklenarcic.indexed-db.delta :as delta]
            [org.clojars.roklenarcic.indexed-db.store :as store]
            [com.fulcrologic.guardrails.core :refer [>defn ? =>]]
            [promesa.core :as p]))

(s/def ::schema-name keyword?)
(s/def ::schema-conf (s/keys :req [::schema-name]))
(s/def ::schema-confs (s/and (s/coll-of ::schema-conf :min-count 1 :kind sequential?)
                             #(delta/unique-vals? % ::schema-name)))

(>defn env->tx
  "From env store, use connection there to start a transaction.

  Returns promise."
  [env schema-name]
  [map? ::schema-name => p/promise?]
  (p/then (get-in env [::connections schema-name ::connection])
          (fn [conn] (store/start-tx conn))))

(>defn tx-on-close [tx]
  [store/tx? => ifn?]
  (store/tx-on-close tx))

(>defn get-entity
  "Take Transaction and returns promise with data of entity."
  [tx ident]
  [store/tx? some? => p/promise?]
  (store/get-entity tx ident))

(>defn get-entities
  "Take Transaction and returns promise with vector of entities."
  [tx idents]
  [store/tx? some? => p/promise?]
  (reduce
    (fn [acc ident]
      (p/then (p/all [acc (get-entity tx ident)]) (partial apply conj)))
    (p/resolved [])
    idents))

(>defn put-entity
  "Take Transaction and returns promise with key of inserted entity."
  [tx ident entity]
  [store/tx? some? some? => p/promise?]
  (store/put-entity tx ident entity))

(>defn update-entity
  "Take entity and update it using f, returning new entity"
  [tx ident f]
  [store/tx? some? ifn? => p/promise?]
  (store/update-entity tx ident f))

(>defn insert-entity
  "Convenience wrapper, also does the tempid mapping logic, returns promise of
  {:tempids tempid->real-id :entity entity :ident ident}

  Assumes that entity is a map."
  [tx entity id-key]
  [store/tx? map? keyword? => p/promise?]
  (let [tempid (id-key entity)
        entity (update entity id-key #(.-id %))
        ident [id-key (id-key entity)]]
    (p/then (store/put-entity tx ident entity)
            (fn [_] {:tempids {tempid (id-key entity)}
                     :ident ident
                     :entity entity}))))

(>defn delete-entity
  "Delete entity"
  [tx ident]
  [store/tx? some? => p/promise?]
  (store/delete-entity tx ident))

(>defn schema-conf [schema-name]
  [::schema-name => ::schema-conf]
  {::schema-name schema-name})

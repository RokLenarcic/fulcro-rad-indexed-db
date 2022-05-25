(ns org.clojars.roklenarcic.indexed-db.delta
  (:require [com.fulcrologic.fulcro.algorithms.tempid :as tempid]
            [com.fulcrologic.guardrails.core :refer [>defn ? => >def]]
            [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.attributes-options :as ao]
            [org.clojars.roklenarcic.indexed-db.store :as store]
            [promesa.core :as p]
            [taoensso.encore :as enc]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

(defn unique-vals? [coll-of-maps k]
  (loop [s #{}
         [m & more] coll-of-maps]
    (if-let [v (some-> (find m k) val)]
      (cond
        (contains? s v) false
        more (recur (conj s v) more)
        :else true))))

(>def ::ident (s/tuple qualified-keyword? some?))
(>def ::diff (s/and map? #(or (contains? % :after) (contains? :before %))))
(>def ::delta (s/every-kv ::ident (s/every-kv qualified-keyword? ::diff)))

(>defn schemas-for-delta
  "Returns Map keyed by ID attribute's schema. We don't support mixed schema entities.
  Delta map for that schema."
  [env delta]
  [map? ::delta => (s/every-kv keyword? ::delta)]
  (let [lookup (::attr/key->attribute env)]
    (reduce
      (fn [acc [ident changes]]
        (assoc-in acc [(-> ident first lookup ao/schema) ident] changes))
      {}
      delta)))

(defn extract-and-fix-temp-id
  [x tempids]
  (if (tempid/tempid? x)
    (do (swap! tempids assoc x (.-id x)) (.-id x))
    (if (and (vector? x) (= (count x) 2))
      (update x 1 extract-and-fix-temp-id tempids)
      x)))

(>defn save-delta
  "Take a connection and a coll of pairs [ident delta-data], returns
  tempids map promise."
  [ptx delta]
  [store/tx? ::delta => any?]
  ;; use simplified schema just use temp ID UUID as real UUID
  ;; and just override, ignore before key
  (p/let [tx ptx
          tempids (atom {})]
    (->> delta
         (map (fn [[ident diff]]
                (let [ident* (extract-and-fix-temp-id ident tempids)
                      entity-updates (enc/map-vals #(-> % :after (extract-and-fix-temp-id tempids)) diff)]
                  (p/as-> (store/get-entity tx ident*) entity
                          (merge entity entity-updates)
                          (store/put-entity tx ident* entity)))))
         p/all
         (p/map (fn [_] @tempids)))))

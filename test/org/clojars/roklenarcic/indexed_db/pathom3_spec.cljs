(ns org.clojars.roklenarcic.indexed-db.pathom3-spec
  (:require [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.attributes-options :as ao]
            [com.fulcrologic.rad.form :as form]
            [org.clojars.roklenarcic.indexed-db.pathom3 :as pathom3]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as indexed-db]
            [clojure.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [com.wsscode.pathom3.connect.operation :as pco]
            [taoensso.timbre :as log]))

(attr/defattr id ::id :int
  {ao/identity? true
   ao/schema :test})

(attr/defattr name ::name :string
  {ao/identities #{::id}
   ao/schema :test})

(pco/defmutation add-test-data
  [env {::keys [id name] :as params}]
  {::pco/output [::id ::name]}
  (p/let [tx (indexed-db/env->tx env :test)]
    (indexed-db/update-entity tx [::id (::id params)] (constantly params))
    params))

(def parser
  (pathom3/processor
    [id name]
    [(indexed-db/schema-conf :test)]
    {:extra-resolvers [add-test-data]}))

(defn ->delta [entity id-key]
  {::form/master-pk id-key
   ::form/id (id-key entity)
   ::form/delta {[id-key (id-key entity)]
                 (reduce-kv
                   (fn [m k v]
                     (assoc m k {:after v}))
                   {}
                   entity)}})

(deftest entity-test
  (testing "Insert and read an entity"
    (async done
      (p/finally
        (p/let [r1 (parser {} `[(add-test-data {::id 1 ::name "Rok"})])
                r2 (parser {} [{[::id 1] [::id ::name]}])
                r3 (parser {} `[(form/save-as-form ~(->delta {::id 1 ::name "Rok X"} ::id))])
                r4 (parser {} [{[::id 1] [::id ::name]}])
                r5 (parser {} `[(form/delete-entity [::id 1])])
                r6 (parser {} [{[::id 1] [::id ::name]}])]
          (is (= {`add-test-data {::id 1 ::name "Rok"}} r1))
          (is (= {[::id 1] {::id 1 ::name "Rok"}} r2))
          (is (= {`form/save-as-form {::id 1, :tempids {}}} r3))
          (is (= {[::id 1] {::id 1 ::name "Rok X"}} r4))
          (is (= {`form/delete-entity {:tempids {}}} r5))
          (is (= {[::id 1] {::id 1}} r6)))
        (fn [_ _] (done))))))

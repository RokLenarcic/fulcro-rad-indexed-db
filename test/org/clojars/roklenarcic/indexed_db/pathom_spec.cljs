(ns org.clojars.roklenarcic.indexed-db.pathom-spec
  (:require [com.fulcrologic.rad.attributes :as attr]
            [com.fulcrologic.rad.attributes-options :as ao]
            [com.fulcrologic.rad.form :as form]
            [org.clojars.roklenarcic.indexed-db.pathom :as pathom]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as indexed-db]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [taoensso.timbre :as log]
            [com.fulcrologic.fulcro.algorithms.tempid :as tempid]))

(attr/defattr id ::id :int
  {ao/identity? true
   ao/schema :test})

(attr/defattr name ::name :string
  {ao/identities #{::id}
   ao/schema :test})

(pc/defmutation add-test-data
  [env params]
  {::pc/params [::id ::name]
   ::pc/output [::id ::name]}
  (p/let [tx (indexed-db/env->tx env :test)]
    (indexed-db/update-entity tx [::id (::id params)] (constantly params))
    params))

(def parser
  (pathom/parser
    [id name]
    [add-test-data]
    [(indexed-db/schema-conf :test)]))

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
      (async/go
        (is (= {`add-test-data {::id 1 ::name "Rok"}}
               (async/<! (parser {} `[(add-test-data {::id 1 ::name "Rok"})]))))
        (is (= {[::id 1] {::id 1 ::name "Rok"}}
               (async/<! (parser {} [{[::id 1] [::id ::name]}]))))
        (is (= {`form/save-as-form {::id 1,
                                    :tempids {}}}
               (async/<! (parser {} `[(form/save-as-form ~(->delta {::id 1 ::name "Rok X"} ::id))]))))
        (is (= {[::id 1] {::id 1 ::name "Rok X"}}
               (async/<! (parser {} [{[::id 1] [::id ::name]}]))))
        (is (= {`form/delete-entity nil}
               (async/<! (parser {} `[(form/delete-entity [::id 1])]))))
        (is (= {[::id 1] {::id 1}}
               (async/<! (parser {} [{[::id 1] [::id ::name]}]))))
        (done)))))

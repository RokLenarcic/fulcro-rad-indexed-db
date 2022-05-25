(ns org.clojars.roklenarcic.indexed-db.store-spec
  (:require [org.clojars.roklenarcic.indexed-db.store :as store]
            [clojure.test :refer [deftest is testing async]]
            [promesa.core :as p]))

(deftest open-tx-test
  (testing "Open TX test"
    (async done
      (p/let [conn (store/connect "test-db" true)
              tx (store/start-tx conn)]
        (is (= (type tx) store/Transaction))
        (is (store/tx? tx))
        (is (store/conn? conn))
        (done)))))

(defn ->tx []
  (p/then (store/connect "test-db" true)
          #(store/start-tx %)))

(deftest write-value-test
  (testing "Writing value test"
    (async done
      (p/let [tx (->tx)
              ret (store/put-entity tx :x 13)
              v (store/get-entity tx :x)]
        (is (= v 13))
        (is (= ret ":x"))
        (done)))))

(deftest update-value-test
  (testing "Update value test"
    (async done
      (p/let [tx (->tx)
              ret (store/update-entity tx :cnt (fnil inc 0))
              ret2 (store/update-entity tx :cnt (fnil inc 0))]
        (is (= (inc ret) ret2))
        (done)))))

(deftest delete-value-test
  (testing "Delete value test"
    (async done
      (p/let [tx (->tx)
              ret (store/put-entity tx :del "X")
              v1 (store/get-entity tx :del)
              _ (store/delete-entity tx :del)
              v2 (store/get-entity tx :del)]
        (is (= v1 "X"))
        (is (= v2 nil))
        (done)))))

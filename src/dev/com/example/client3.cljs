(ns com.example.client3
  (:require [org.clojars.roklenarcic.indexed-db.pathom3 :as idb-pathom3]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as core]
            [com.wsscode.pathom3.connect.operation :as pco]
            [com.example.client.attributes :as a]
            [promesa.core :as promesa]))

(pco/defresolver
  some-res [_ _]
  {::pco/output [{::all-objects [::a/id]}]}
  {::all-objects [{::a/id 1} {::a/id 2}]})

(def resolvers [some-res])

(defn parser [resolvers]
  (idb-pathom3/processor
    [a/id a/email]
    [(core/schema-conf :db)]
    {:extra-resolvers resolvers
     :log-requests? true
     :log-responses? true}))

(defn run-q [env q]
  (let [a (atom nil)]
    (promesa/then ((parser resolvers) env q) #(reset! a %))
    a))

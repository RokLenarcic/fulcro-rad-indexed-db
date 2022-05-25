(ns com.example.client
  (:require [cljs.core.async :as async]
            [com.fulcrologic.rad.form :as form]
            [org.clojars.roklenarcic.indexed-db.store :as s]
            [org.clojars.roklenarcic.indexed-db.pathom :as impl]
            [org.clojars.roklenarcic.fulcro-rad-indexed-db :as core]
            [com.example.client.attributes :as a]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :include-macros true :as pc]))

(pc/defresolver
  some-res [_ _]
  {::pc/output [{::all-objects [::a/id]}]}
  {::all-objects [{::a/id 1} {::a/id 2}]})

(defn resolvers []
  (cons some-res
        (impl/generate-resolvers [a/id a/email] :db)))

(defn parser [resolvers]
  (p/async-parser
    {::p/mutate pc/mutate-async
     ::p/env {::p/reader [p/map-reader pc/async-reader2 pc/index-reader
                          pc/open-ident-reader p/env-placeholder-reader]
              ::p/placeholder-prefixes #{">"}
              ::pc/mutation-join-globals [:tempids]}
     ::p/plugins [(pc/connect-plugin {::pc/register resolvers})
                  (form/pathom-plugin (impl/wrap-save) (impl/wrap-delete))
                  (impl/pathom-plugin [(core/schema-conf :db)])]}))

(defn run-q [env q] (async/go (println (async/<! ((parser (resolvers)) env q)))))

(defn init [] (println "Init"))


(defn refresh [])

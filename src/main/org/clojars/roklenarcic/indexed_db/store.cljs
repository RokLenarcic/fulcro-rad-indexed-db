(ns org.clojars.roklenarcic.indexed-db.store
  (:require [promesa.core :as p]
            [com.fulcrologic.guardrails.core :refer [>defn ? =>]]
            [cljs.reader :refer [read-string]]))

(defprotocol Tx
  "Defines transaction protocol."
  (tx-on-close [this])
  (get-entity [this ident] "Take Transaction and returns promise with data of entity.")
  (put-entity [this ident v] "Take Transaction and returns promise with key of inserted entity.")
  (update-entity [this ident f] "Take entity and update it using f, returning the new entity")
  (delete-entity [this ident] "Delete entity"))

(defprotocol Conn (start-tx [this]))

(defn ser
  "Serialize obj"
  [x] (pr-str x))

(defn deser
  "Extract property from o and deserialize str"
  [x] (read-string x))

(defn <! [r]
  (let [p (p/deferred)]
    (set! (.-onerror r)
          (fn [e]
            (p/reject! p (ex-info "IndexedDB error" {:type :db-error :error (.-target e)})) ))
    (set! (.-onsuccess r) (fn [e]
                            (p/resolve! p (-> e .-target .-result))))
    p))

(defn idb [] js/window.indexedDB)

(defn open-database
  "Open database named with n, version"
  [n ver]
  (.open (idb) n ver))

(defn create-fulcro-db
  [db-name store-name]
  (let [req (open-database db-name 1)]
    (set! (.-onupgradeneeded req)
          (fn upgrade-handler [e]
            (.createObjectStore (-> e .-target .-result) (or store-name "fulcro") #js {:keyPath "key"})))
    (<! req)))

(defn ident->key [ident] (if (string? ident) ident (pr-str ident)))

;; pcompleted has a promise that succeeds or fails when transaction is finished/aborted/errored
(defrecord Transaction [object-store pcompleted]
  Tx
  (tx-on-close [this] pcompleted)
  (get-entity [this ident]
    (p/then (<! (.get object-store (ident->key ident)))
            (fn [x]
              (when-let [m (js->clj x :keywordize-keys true)]
                (let [obj (deser (:obj m))]
                  (if (satisfies? obj IWithMeta)
                    (with-meta obj (deser (:meta m)))
                    obj))))))
  (put-entity [this ident entity]
    (<! (.put object-store #js {:key (ident->key ident) :obj (ser entity) :meta (ser (meta entity))})))
  (update-entity [this ident f]
    (p/let [entity (get-entity this ident)
            _ (put-entity this ident (f entity))]
      entity))
  (delete-entity [this ident]
    (p/then (<! (.delete object-store (ident->key ident)))
            (constantly nil))))

(defrecord Connection [db store-name rw?]
  Conn
  (start-tx [this]
    (let [p (p/deferred)
          tx (.transaction db [store-name] (if rw? "readwrite" "readonly"))]
      (set! (.-oncomplete tx) (fn [e] (p/resolve! p nil)))
      (set! (.-onabort tx) (fn [e] (p/reject! p :aborted)))
      (->Transaction (.objectStore tx store-name) p))))

(>defn connect
  "Connects to the Fulcro Store and DB in readwrite or readonly mode, returns Storage promise."
  ([db-name rw?]
   [string? boolean? => p/promise?]
   (connect db-name "fulcro" rw?))
  ([db-name store-name rw?]
   [string? string? boolean? => p/promise?]
   (-> (create-fulcro-db db-name store-name)
       (p/then (fn [db] (->Connection db store-name rw?))))))

(defn tx? [x] (satisfies? Tx x))
(defn conn? [x] (satisfies? Conn x))

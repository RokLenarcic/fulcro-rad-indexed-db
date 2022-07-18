# Fulcro RAD IndexedDB Storage

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.roklenarcic/fulcro-rad-indexed-db.svg)](https://clojars.org/org.clojars.roklenarcic/fulcro-rad-indexed-db)

This is a CLJS library to use Browser's IndexedDB API to use as a RAD storage provider.

The current state is that library provides:
- Basic operations on Indexed DB
- One or many schemas
- Save and Delete Middleware for Pathom 2 and 3
- ID resolvers for Pathom Connect 2 and resolvers for Pathom 3
- Pathom 2 parser function
- Pathom 3 processor
- A Fulcro Remote using the Pathom 2 parser
- A Fulcro Remote using the Pathom 3 processor

## Usage

You can use a batteries-included Fulcro remote and Pathom parser, which
requires minimal configuration on your part, or you can use bits and pieces
such as the middlewares and resolvers and include those in a parser or remote
of your own.

### Requires

- `[org.clojars.roklenarcic.fulcro-rad-indexed-db :as indexed-db]`: most utility functions you'll need to interact with IndexedDB
- `[org.clojars.roklenarcic.indexed-db.pathom :as idb-pathom]`: contains Pathom 2 primitives
- `[org.clojars.roklenarcic.indexed-db.pathom :as idb-pathom3]`: contains Pathom 3 primitives

The basis of operation is that data you're using should be attributes in one or more schemas.

For attributes from these specified schemas, the premade Pathom parser, plugin and remote will provide and register 
resolvers:
- entity resolvers (ID attr -> linked members)
- RAD form resolvers 
- resolvers for attributes with ::attr/resolve 

### Using remote Pathom 3

```clojure
(defn remote []
  (idb-pathom3/remote
    all-attributes ; put all attributes here
    [(indexed-db/schema-conf :my-schema)] ; coll of schema configurations
    ; list additional resolvers, you want to use indexed DB with
    {:extra-resolvers (flatten [m.invoice/resolvers])
     :log-requests? true
     :log-responses? true}))

(defonce app (reset! SPA (rad-app/fulcro-rad-app {:remotes {:remote (remote)}})))
```

### Using remote Pathom 2

```clojure
(defn remote []
  (idb-pathom/remote
    all-attributes ; put all attributes here
    ; list additional resolvers, you want to use indexed DB with
    (flatten [m.invoice/resolvers])  
    [(indexed-db/schema-conf :my-schema)])) ; coll of schema configurations

(defonce app (reset! SPA (rad-app/fulcro-rad-app {:remotes {:remote (remote)}})))
```

If you want to use parts or this remote and make your own, look at implementation of the remote function.

### Schema configurations

The only property of a schema configuration is the schema name, a keyword. Use `indexed-db/schema-conf` to construct
these.

## Writing your own mutations and resolvers

Loading things other than singular entities such as lists of entities, and also inserting, updating and deleting
entities through mutations other than form mutations will require that you write your own resolvers.

### Working with the interface

The `:org.clojars.roklenarcic.fulcro-rad-indexed-db/connections` key in env is a map of connections, 
one for each schema configured. You will need to grab one of these, create a Transaction, then use some operations in `indexed-db` namespace.

**All operations return Promesa promise. You should return a promise from your mutations and resolvers.**

Note that in CLJS you can only use async pathom parser.

### Example: inserting an entity and adding it to a list

```clojure
(pc/defmutation add-invoice
  [env params]
  {::pc/params [::m/id]
   ::pc/output [::m/id ::m/name-to]}
  (p/let [tx (indexed-db/env->tx env :my-schema)
          {:keys [tempids entity]} (indexed-db/insert-entity tx params ::m/id)
          _ (indexed-db/update-entity tx ::m/list #(conj (or % []) (::m/id entity)))]
    (assoc entity :tempids tempids)))
```

1. First we open a transaction to my-schema using the connections in the env with `env->tx`, the resulting promise is unwrapped by `p/let`
2. The entity is then inserted, returning tempids map, the entity (with new ID)
3. List key is updated, ID is added to the list
4. We return new entity with tempids map
5. **The use of p/let neatly unwraps all promises and make sure that operations proceed serially.**

### Example: loading a list 

```clojure
(pc/defresolver get-invoice-list
    [env _]
    {::pc/input #{}
     ::pc/output [{::m/list [::m/id]}]}
  (p/let [tx (indexed-db/env->tx env :my-schema)
          ids (indexed-db/get-entity tx ::m/list)]
    ;; and update the list with that
    {::m/list (mapv (partial hash-map ::m/id) ids)}))
```

1. We open tx and get the list
2. We return a coll of maps like `{::m/id id}`
3. If query requested fields other than ID, Pathom Connect will use ID->entity resolver to select each entity, so we don't have to write that part

This is all neat, but this produces a lot of queries, one for the list and then `n` queries, one for each item in list.
This cannot be improved much currently, but there is another aspect: **each resolver opens a transaction**. We can change this to
load all the data in one transaction, see below.

### Example: loading a list with properties in one transaction + cleanup

```clojure
(defn get-list 
  "Generic function to fetch list of IDs + linked entities"
  [ptx list-key id-key]
  (p/let [tx ptx
          ids (indexed-db/get-entity tx list-key)
          entities (indexed-db/get-entities tx (map (partial vector id-key) ids))
          ;; remove entities from list that were deleted
          filtered (filterv some? entities)]
    ;; and update the list with that
    (p/then (indexed-db/put-entity tx list-key (mapv id-key filtered))
            (constantly {list-key filtered}))))

(pc/defresolver get-invoice-list
  [env _]
  {::pc/input #{}
   ::pc/output [{::m/list
                 (mapv ao/qualified-key [m/id m/name-to m/addr-to m/addr2-to m/amount m/description
                                         m/due-date m/iban-to m/code m/ref-to])}]}
  (get-list (indexed-db/env->tx env :my-schema) ::m/list ::m/id))
```

Here we load the list, the entities, remove any entities that have deleted from the list,
update the list all in one transaction. The resolver offers many more properties, so additional calls
to other resolvers won't be needed for these.

## Dev

Run the project's tests (they'll fail until you edit them):

    $ clojure -T:build test

Run the project's CI pipeline and build a JAR (this will fail until you edit the tests to pass):

    $ clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized dependencies inside the `META-INF`
directory inside `target/classes` and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally (requires the `ci` task be run first):

    $ clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` environment
variables (requires the `ci` task be run first):

    $ clojure -T:build deploy

Your library will be deployed to org.clojars.roklenarcic/fulcro-rad-indexed-db on clojars.org by default.

## License

Copyright © 2022 Rok Lenarčič

Distributed under the Eclipse Public License version 1.0.

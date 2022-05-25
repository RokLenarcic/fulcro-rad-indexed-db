(ns com.example.client.attributes
  (:require [com.fulcrologic.rad.attributes :include-macros true :as attr]
            [com.fulcrologic.rad.attributes-options :as ao]))

(attr/defattr id ::id :int
  {ao/schema :db
   ao/identity? true})

(attr/defattr email ::email :string
  {ao/schema :db
   ao/identities #{::id}})

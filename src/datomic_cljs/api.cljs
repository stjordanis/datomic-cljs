(ns datomic-cljs.api
  (:require [datomic-cljs.http :as http]
            [cljs.core.async :as async :refer [>! <!]]
            [cljs.reader :as reader])
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [datomic-cljs.macros :refer [>!x]]))

(defprotocol IQueryDatomic
  (execute-query [db query-str inputs]))

(defprotocol IHaveEntities
  (get-entity [db eid]))

(defprotocol ITransactDatomic
  (execute-transaction! [db tx-data-str]))

(defprotocol IBasis
  (get-basis [db]))

(defrecord DatomicConnection [hostname port db-alias]
  ITransactDatomic
  (execute-transaction! [_ tx-data-str]
    ;; TODO: return database values as :db-before and :db-after
    (http/receive-edn
     (http/post {:protocol "http:"
                 :hostname hostname
                 :port port
                 :path (str "/data/" db-alias "/")
                 :headers {"Accept" "application/edn"
                           "Content-Type" "application/x-www-form-urlencoded"}}
                {:tx-data tx-data-str}))))

(defrecord DatomicDB [connection implicit-args]
  IQueryDatomic
  (execute-query [_ q-str inputs]
    (let [args-str (-> implicit-args
                       (cons inputs)
                       (vec)
                       (prn-str))
          encoded-q-str (http/encode-query {:q q-str :args args-str})
          path (str "/api/query?" encoded-q-str)]
      (http/receive-edn
       (http/get {:protocol "http:"
                  :hostname (:hostname connection)
                  :port (:port connection)
                  :path path
                  :headers {"Accept" "application/edn"}}))))

  IHaveEntities
  (get-entity [_ eid]
    (let [path (->> (http/encode-query {:e eid
                                        :as-of (:as-of implicit-args)
                                        :since (:since implicit-args)})
                    (str "/data/" (:db/alias implicit-args) "/-/entity?"))]
      (http/receive-edn
       (http/get {:protocol "http:"
                  :hostname (:hostname connection)
                  :port (:port connection)
                  :path path
                  :headers {"Accept" "application/edn"}}))))

  IBasis
  (get-basis [_]
    (let [c-basis (async/chan 1)]
      (go
        (if (:as-of implicit-args)
          (>!x c-basis (:as-of implicit-args))
          (let [res (<! (http/receive-edn
                         (http/get {:protocol "http:"
                                    :hostname (:hostname connection)
                                    :port (:port connection)
                                    :path (str "/data/" (:db-alias connection)
                                               "/" (or (:as-of implicit-args) "-") "/")
                                    :headers {"Accept" "application/edn"}})))]
            (if (instance? js/Error res)
              (>!x c-basis res)
              (>!x c-basis (:basis-t res))))))
      c-basis)))

(defn connect
  "Create an abstract connection to a Datomic REST service by passing
   the following arguments:

     hostname, e.g. localhost;
     port, the port on which the REST service is listening;
     alias, the transactor alias;
     dbname, the name of the database being connected to."
  [hostname port alias dbname]
  (->DatomicConnection hostname port (str alias "/" dbname)))

(defn create-database
  "Create or connect to a Datomic database via a Datomic REST service
   by passing the following arguments:

     hostname, e.g. localhost;
     port, the port on which the REST service is listening;
     alias, the transactor alias;
     dbname, the name of the database being created.

   Returns a core.async channel eventually containing a database
   connection (as if using datomic-cljs.api/connect), or an error."
  [hostname port alias dbname]
  (let [c-conn (async/chan 1)]
    (go
      (let [{:keys [status] :as res}
            (<! (http/post {:protocol "http:"
                            :hostname hostname
                            :port port
                            :path (str "/data/" alias "/")
                            :headers {"Accept" "application/edn"
                                      "Content-Type" "application/x-www-form-urlencoded"}}
                           {:db-name dbname}))]
        (cond (instance? js/Error res)
                (>!x c-conn res)
              (or (= status 200) (= status 201))
                (>!x c-conn (connect hostname port alias dbname))
              :else
                (>!x c-conn (js/Error.
                             (str "Could not create or connect to db: " status))))))
    c-conn))

(defn db
  "Creates an abstract Datomic value that can be queried."
  [{:keys [db-alias] :as connection}]
  (->DatomicDB connection {:db/alias db-alias}))

(defn as-of
  "Returns the value of the database as of some point t, inclusive.
   t can be a transaction number, transaction ID, or inst."
  [{:keys [connection implicit-args]} t]
  (->DatomicDB connection (assoc implicit-args :as-of t)))

(defn as-of-t
  "Returns the as-of point, or nil if none."
  [{{as-of :as-of} :implicit-args}]
  as-of)

(defn since
  "Returns the value of the database since some point t, exclusive.
   t can be a transaction number, transaction ID, or inst."
  [{:keys [connection implicit-args]} t]
  (->DatomicDB connection (assoc implicit-args :since t)))

(defn since-t
  "Returns the since point, or nil if none."
  [{{since :since} :implicit-args}]
  since)

(defn basis-t
  "Returns a core.async channel eventually containing the t of the
   the most recent transaction available via this db value."
  [db]
  (get-basis db))

(defn q
  "Execute a query against a database value with inputs. Returns a
   core.async channel that will contain the result of the query, and
   will be closed when the query is complete."
  [query db & inputs]
  (execute-query db (prn-str query) inputs))

(defn entity
  "Returns a map of the entity's attributes for the given id."
  [db eid]
  (get-entity db eid))

(defn transact
  "Submits a transaction to the database for writing. The transaction
   data is sent to the Transactor and, if transactAsync, processed
   asynchronously.

   tx-data is a list of lists, each of which specifies a write
   operation, either an assertion, a retraction or the invocation of
   a data function. Each nested list starts with a keyword identifying
   the operation followed by the arguments for the operation.

   Returns a core.async channel that will contain a map with the
   following keys:

     :db-before, the database value before the transaction;
     :db-after, the database value after the transaction;
     :tx-data, the collection of Datums produced by the transaction;
     :tempids, an argument to resolve-tempids."
  [connection tx-data]
  (execute-transaction! connection (if (string? tx-data)
                                     tx-data
                                     (prn-str tx-data))))


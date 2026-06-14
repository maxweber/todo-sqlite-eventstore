(ns app.event-store
  "S3-backed event store.

   Events are appended in commits using simplemono.eventstore.s3. SQLite is still used for
   read-model projections, but events are the source of truth and live in a
   Tigris Data bucket."
  (:require [clojure.string :as str]
            [simplemono.eventstore.protocols :as es]
            [simplemono.eventstore.s3 :as s3]
            [simplemono.eventstore.s3-packs :as s3-packs]
            [next.jdbc :as jdbc]))

(defn- env
  [k]
  (let [v (System/getenv k)]
    (when-not (str/blank? v)
      v)))

(defn- event-store-config
  []
  (let [bucket (env "EVENT_STORE_BUCKET")]
    (when (str/blank? bucket)
      (throw (ex-info "Missing S3 event-store bucket"
                      {:required-env "EVENT_STORE_BUCKET"})))
    (cond-> {:bucket bucket
             :prefix (or (env "EVENT_STORE_PREFIX") "todo")}
      (env "EVENT_STORE_ENDPOINT")
      (assoc :endpoint (env "EVENT_STORE_ENDPOINT"))

      (env "EVENT_STORE_REGION")
      (assoc :region (env "EVENT_STORE_REGION"))

      (env "EVENT_STORE_ACCESS_KEY_ID")
      (assoc :access-key-id (env "EVENT_STORE_ACCESS_KEY_ID")
             :secret-access-key (env "EVENT_STORE_SECRET_ACCESS_KEY")))))

(defonce config
  (delay (event-store-config)))

(defonce client
  (delay
    (s3/client (select-keys @config [:endpoint
                                     :region
                                     :access-key-id
                                     :secret-access-key]))))

(defn- store-options
  []
  {:client @client
   :bucket (:bucket @config)
   :prefix (:prefix @config)
   :headers {"X-Tigris-Consistent" "true"}})

(defonce store
  (delay
    (s3/store (store-options))))

(defonce replay-store
  (delay
    (s3-packs/store (store-options))))

(defn- next-commit-number
  []
  (if-some [latest (es/latest-commit-number @store)]
    (inc latest)
    0))

(defn append-events!
  "Append events as a single commit and return the stored commit.

   If another writer wins the chosen commit number, retry with the new head."
  [events]
  (let [commit {:commit/id (random-uuid)
                :commit/created-at (java.util.Date.)
                :commit/events (vec events)}]
    (loop []
      (let [commit-number (next-commit-number)]
        (if (es/try-append! @store commit-number commit)
          (assoc commit :commit/number commit-number)
          (recur))))))

(defn pack-completed-ranges!
  "Create any missing full 1000-commit S3 packs for faster replay."
  []
  (s3-packs/pack-completed-ranges! (store-options) @store))

(defn get-all-events
  "Get all events in commit order using the pack-aware replay store."
  []
  (if-some [latest (es/latest-commit-number @replay-store)]
    (->> (range 0 (inc latest))
         (mapcat #(:commit/events (es/get-commit @replay-store %)))
         vec)
    []))

(defn drop-todos-table!
  "Drop the todos table for replay. Used when rebuilding the read-model."
  [connectable]
  (jdbc/execute! connectable ["DROP TABLE IF EXISTS todos"]))

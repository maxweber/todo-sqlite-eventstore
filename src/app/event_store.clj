(ns app.event-store
  "S3-backed event store.

   Events are appended in commits using the pack-aware S3 EventStore. SQLite is
   used only for read-model projections; events are the source of truth."
  (:require [clojure.string :as str]
            [simplemono.eventstore.protocols :as es]
            [simplemono.eventstore.s3 :as s3]
            [simplemono.eventstore.s3-packs :as s3-packs]))

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

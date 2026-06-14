(ns app.event-processor
  "Processes events: appends them to the S3 event store and applies projections
   to the SQLite todos table."
  (:require [app.event-store :as store]
            [app.projections.core :as proj]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

(defn process-events!
  "Process events within a single SQLite transaction.

   Takes world-map w with :db/ds (datasource).
   Takes command-result which is {:ok events} or {:error msg}.

   Events are appended before projections. If projection fails, the S3 event
   store remains the source of truth and the read-model can be rebuilt by replay."
  [w command-result]
  (if-let [events (:ok command-result)]
    (let [ds (:db/ds w)
          projection-lookup (proj/build-projection-lookup
                              ((:system/get-register w)))]

      ;; 1. Store events durably. This is intentionally outside the SQLite
      ;; transaction because the event store is now the source of truth.
      (when (seq events)
        (store/append-events! events))

      ;; 2. Apply projections.
      (jdbc/with-transaction [tx ds]
        (let [stmts (vec (mapcat (partial proj/apply-event projection-lookup)
                                 events))]
          (doseq [stmt stmts]
            (jdbc/execute! tx (sql/format stmt)))))

      ;; 3. Return success
      (assoc w
             :command/result {:success? true
                              :aggregate-id (:aggregate-id command-result)}
             :event-processor/events events))

    ;; Error case - no events to process
    (assoc w
           :command/result {:success? false
                            :error (:error command-result)})))

(defn replay-all-events!
  "Rebuild todos read-model by replaying all events from the S3 event store.

   DESTRUCTIVE: Drops the todos table and rebuilds it from events.
   Read-model writes happen in a single SQLite transaction.

   Returns {:replayed count}.

   2-arity takes ds and projection-lookup directly.
   1-arity derives projection-lookup from system register."
  ([ds]
   (let [get-register (requiring-resolve 'app.system.register/get-register)]
     (replay-all-events! ds (proj/build-projection-lookup
                              (get-register)))))
  ([ds projection-lookup]
   (jdbc/with-transaction [tx ds]
     ;; Read events first
     (let [events (store/get-all-events)]

       ;; Drop and recreate todos table
       (store/drop-todos-table! tx)
       (jdbc/execute! tx ["
        CREATE TABLE IF NOT EXISTS todos (
          id TEXT PRIMARY KEY,
          text TEXT NOT NULL,
          completed INTEGER NOT NULL DEFAULT 0
        )"])

       ;; Replay each event
       (doseq [event events]
         (let [stmts (proj/apply-event projection-lookup event)]
           (doseq [stmt stmts]
             (jdbc/execute! tx (sql/format stmt)))))

       {:replayed (count events)}))))

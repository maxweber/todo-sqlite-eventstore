(ns app.event-processor
  "Appends events to the EventStore and catches SQLite projections up."
  (:require [app.db :as db]
            [app.event-store :as store]
            [simplemono.sqlite-event-projection :as projection]))

(defn catch-up!
  [ds register]
  (projection/catch-up! {:event-store @store/store
                         :db/ds ds
                         :projection/version db/projection-version
                         :projection/register register}))

(defn process-events!
  "Append command events to the EventStore, then catch SQLite projections up."
  [w command-result]
  (if-let [events (:ok command-result)]
    (let [ds (:db/ds w)
          register ((:system/get-register w))]
      (when (seq events)
        (store/append-events! events))
      (catch-up! ds register)
      (assoc w
             :command/result {:success? true
                              :aggregate-id (:aggregate-id command-result)}
             :event-processor/events events))
    (assoc w
           :command/result {:success? false
                            :error (:error command-result)})))

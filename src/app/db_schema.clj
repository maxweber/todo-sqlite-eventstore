(ns app.db-schema
  (:require [next.jdbc :as jdbc]))

(defn ensure-schema!
  "Creates the read-model tables if they don't exist."
  [ds]
  (jdbc/execute! ds ["
    CREATE TABLE IF NOT EXISTS todos (
      id TEXT PRIMARY KEY,
      text TEXT NOT NULL,
      completed INTEGER NOT NULL DEFAULT 0
    )"]))

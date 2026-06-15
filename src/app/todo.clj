(ns app.todo
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [app.part :as part]
            [app.event-processor :as event-processor]
            [app.commands.todo :as cmd]
            [app.projections.todo :as proj]))

(defn query-todos
  [w]
  (let [rows (jdbc/execute! (:db/ds w)
               (sql/format {:select [:id :text :completed]
                            :from [:todos]})
               {:builder-fn rs/as-unqualified-maps})]
    (assoc w
           :query/result
           {:todos (mapv (fn [row]
                           {:id (parse-uuid (:id row))
                            :text (:text row)
                            :completed (= 1 (:completed row))})
                         rows)})))

(defn prepare
  "Prepare world-map with a caught-up SQLite read model."
  [w]
  (let [w (part/add-ds w)]
    (event-processor/catch-up! (:db/ds w) ((:system/get-register w)))
    w))

(def register
  [{:query/kind :query/todos
    :query/fn (comp #'query-todos
                    prepare)}
   {:command/kind :command/add-todo
    :command/fn (comp (part/execute-command! #'cmd/add-todo)
                      prepare)}
   {:command/kind :command/toggle-todo
    :command/fn (comp (part/execute-command! #'cmd/toggle-todo)
                      prepare)}
   {:command/kind :command/delete-todo
    :command/fn (comp (part/execute-command! #'cmd/delete-todo)
                      prepare)}
   {:projection/create #'proj/create-todos}
   {:projection/event-kind :todo/created
    :projection/fn #'proj/todo-created}
   {:projection/event-kind :todo/completed
    :projection/fn #'proj/todo-completed}
   {:projection/event-kind :todo/uncompleted
    :projection/fn #'proj/todo-uncompleted}
   {:projection/event-kind :todo/deleted
    :projection/fn #'proj/todo-deleted}])

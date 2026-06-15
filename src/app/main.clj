(ns app.main
  (:require [nrepl.server :as nrepl]
            [cider.nrepl]
            [app.system.atom :as system-atom]
            [app.system.register :as system-register]
            [parts.httpkit.server :as httpkit]
            [app.db :as db]
            [app.event-processor :as event-processor])
  (:gen-class))

(defn start!
  []
  (event-processor/catch-up! (db/get-ds) (system-register/get-register))
  (let [nrepl-server (nrepl/start-server
                       :bind "0.0.0.0"
                       :port 4000
                       :handler cider.nrepl/cider-nrepl-handler)]
    (swap! system-atom/system
           assoc
           :log/log tap>
           :system/get-register #'system-register/get-register)
    (httpkit/start! system-atom/system)
    (add-tap prn)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread.
                        (fn []
                          (nrepl/stop-server nrepl-server))))))

(defn -main
  [& _args]
  (start!))

(comment
  (start!))

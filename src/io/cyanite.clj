(ns io.cyanite
  "Main cyanite namespace"
  (:gen-class)
  (:require [io.cyanite.config          :as config]
            [io.cyanite.signals         :as sig]
            [io.cyanite.input           :as input]
            [io.cyanite.index           :as index]
            [io.cyanite.engine.queue    :as queue]
            [io.cyanite.store           :as store]
            [com.stuartsierra.component :as component]
            [io.cyanite.engine          :refer [map->Engine]]
            [io.cyanite.api             :refer [map->Api]]
            [unilog.config              :refer [start-logging!]]
            [spootnik.uncaught          :refer [uncaught]]
            [clojure.tools.logging      :refer [info warn]]
            [clojure.tools.cli          :refer [cli]]))

(set! *warn-on-reflection* true)

(defn get-cli
  "Call cli parsing with our known options"
  [args]
  (try
    (cli args
         ["-h" "--help" "Show help" :default false :flag true]
         ["-f" "--path" "Configuration file path" :default nil]
         ["-q" "--quiet" "Suppress output" :default false :flag true])
    (catch Exception e
      (binding [*out* *err*]
        (println "Could not parse arguments: " (.getMessage e)))
      (System/exit 1))))

(defn build-components
  "Build a list of components from data.
   Extracts key k from system and yield
   an updated system with top-level keys.
   components are created by call f on them
   options."
  [system k f]
  (if (seq (get system k))
    (merge (dissoc system k)
           (reduce merge {} (map (juxt :type f) (get system k))))
    ;; When key didn't exist in map, create, call the constructor
    ;; on an empty option map
    (assoc system k (f {}))))

(defn config->system
  "Parse yaml then enhance config"
  [path quiet?]
  (try
    (when-not quiet?
      (println "starting with configuration: " path))
    (let [config (config/load-path path)]
      (start-logging! (merge config/default-logging (:logging config)))
      (-> config
          (dissoc :logging)
          (build-components :input input/build-input)
          (update :engine #(component/using (map->Engine %) [:index
                                                             :store
                                                             :queues]))
          (update :queues queue/queue-engine)
          (update :api #(component/using (map->Api {:options %}) [:index
                                                                  :store
                                                                  :queues
                                                                  :engine]))
          (update :index index/build-index)
          (update :store store/build-store)))))

(defn -main
  "Our main function, parses args and launches appropriate services"
  [& args]
  (let [[{:keys [path help quiet]} args banner] (get-cli args)]

    (when help
      (println banner)
      (System/exit 0))

    (let [system  (config->system path quiet)]
      (info "installing signal handlers")
      (sig/with-handler :term
        (info "caught SIGTERM, quitting")
        (component/stop-system system)
        (info "all components shut down")
        (System/exit 0))

      (sig/with-handler :hup
        (info "caught SIGHUP, reloading")
        (component/stop-system system))
      (info "ready to start system")

      (component/start-system system)))
  nil)

;; Install our uncaught exception handler.
(uncaught e (warn e "uncaught exception"))

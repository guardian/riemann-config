; -*- mode: clojure; -*-
; vim: filetype=clojure

(def version "3.0.0")

(def alerta-endpoints
  {:alert "http://alerta:8080/api/alert"
   :heartbeat "http://alerta:8080/api/heartbeat"})

(defn post-to-alerta
  "POST to the Alerta REST API."
  [url request]
  (let [event-url url
        event-json (json/generate-string request)]
    (client/post event-url
                 {:body event-json
                  :socket-timeout 5000
                  :conn-timeout 5000
                  :content-type :json
                  :accept :json
                  :throw-entire-message? true})))

(defn format-ganglia-event
  "Formats an event for Alerta."
  [event]
  {
   :origin (str "riemann/" hostname)
   :resource
   (if (.contains (:service event) "-")
     (let [[_ instance] (clojure.string/split (:service event) #"-" 2)]
       (str (:resource event) ":" instance))
     (:resource event))
   :event (get event :event (:service event))
   :group (get event :group "Performance")
   :value (str (:metric event))
   :severity (:state event)
   :environment (get event :environment "INFRA")
   :service [(get event :grid "Common")]
   :tags (:tags event)
   :text (:description event)
   :type (:type event)
   :attributes {:ipAddress (:ip event)}
   :rawData event})

(defn format-graphite-event
  "Formats an event for Alerta."
  [event]
  {
   :origin (str "riemann/" hostname)
   :resource (:resource event)
   :event (get event :event (:service event))
   :group (get event :group "Performance")
   :value (str (:metric event))
   :severity (:state event)
   :environment (get event :environment "PROD")
   :service [(get event :origin "Unknown")]
   :tags (:tags event)
   :text (:description event)
   :type (:type event)
   :rawData event})

(defn alerta
  "Creates an alerta adapter.
  (changed-state (alerta))"
  [opts]
  (let [opts (merge {:socket-timeout 5000
                     :conn-timeout 5000 } opts)]
    (fn [event]
      (case (:type event)
        "gangliaAlert" (post-to-alerta (:alert alerta-endpoints) (format-ganglia-event event))
        "graphiteAlert" (post-to-alerta (:alert alerta-endpoints) (format-graphite-event event))
        (post-to-alerta (:alert alerta-endpoints) (format-ganglia-event event))))))

(defn heartbeat [e] (post-to-alerta
                      (:heartbeat alerta-endpoints)
                      {:origin (str "riemann/" hostname)
                       :tags [version]
                       :type "Heartbeat"}))

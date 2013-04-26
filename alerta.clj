; -*- mode: clojure; -*-
; vim: filetype=clojure

(def version "1.0.0")

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

(def alerta-endpoints
	{:alert "http://monitoring/alerta/api/v2/alerts/alert.json"
	:heartbeat "http://monitoring/alerta/api/v2/heartbeats/heartbeat.json"})

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

(defn format-alerta-event
  "Formats an event for Alerta."
  [event]
  {
   :origin (str "riemann/" hostname)
   :resource (:host event)
   :event (get event :event (:service event))
   :group (get event :group "Performance")
   :value (:metric event)
   :severity (:state event)
   :environment [(get event :environment "INFRA")]
   :service [(get event :grid "Common")]
   :tags (:tags event)
   :text (:description event)
   :rawData event})

(defn alerta
  "Creates an alerta adapter.
    (changed-state (alerta))"
  [e]
  (post-to-alerta (:alert alerta-endpoints) (format-alerta-event e)))

(defn heartbeat [e] (post-to-alerta
	(:heartbeat alerta-endpoints)
	{:origin (str "riemann/" hostname)
	   :version version
	   :type "Heartbeat"}))

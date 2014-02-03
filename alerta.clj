; -*- mode: clojure; -*-
; vim: filetype=clojure

(def version "1.0.0")

(def alerta-endpoints
	{:alert "http://monitoring:8080/alerta/api/v2/alerts/alert.json"
	:heartbeat "http://monitoring:8080/alerta/api/v2/heartbeats/heartbeat.json"})

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

(defn key-value-split
  "Split on equals, always returning two values."
  [s]
  (if (.contains s "=") (clojure.string/split s #"=" 2) [s ""]))

(defn format-alerta-event
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
   :value (:metric event)
   :severity (:state event)
   :environment [(get event :environment "INFRA")]
   :service [(get event :grid "Common")]
   :tags (into {} (map #(key-value-split %) (:tags event)))
   :text (:description event)
   :type (:type event)
   :moreInfo
    (if-let [ip (:ip event)]
      (str "ssh -A " ip)
      "IP address not available" )
   :rawData event})

(defn alerta
  "Creates an alerta adapter.
    (changed-state (alerta))"
  [opts]
  (let [opts (merge {:socket-timeout 5000
                     :conn-timeout 5000 } opts)]
  (fn [event]
    (when (:metric event)
      (post-to-alerta (:alert alerta-endpoints) (format-alerta-event event))))))

(defn heartbeat [e] (post-to-alerta
	(:heartbeat alerta-endpoints)
	{:origin (str "riemann/" hostname)
	   :version version
	   :type "Heartbeat"}))


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
   :origin "riemann"
   :resource (:host event)
   :event (:service event)
   :group "Performance"      ; parse from metric name or tags?
   :value (:metric event)
   :severity (:state event)
   :environment 
   	[(if-let [env-tag (first (filter #(re-matches #"^environment:.*" %) (:tags event)))]
   		(last (clojure.string/split env-tag #":"))
   		"INFRA"
   	)]
   :service
    [(if-let [env-tag (first (filter #(re-matches #"^service:.*" %) (:tags event)))]
   		(last (clojure.string/split env-tag #":"))
   		"Common"
   	)]
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

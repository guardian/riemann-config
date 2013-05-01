; -*- mode: clojure; -*-
; vim: filetype=clojure
(require '[clj-http.client :as client] 
		 '[cheshire.core :as json]
		 '[riemann.query :as query])

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

(include "alerta.clj")

; configure the various servers that we listen on
(tcp-server)
(udp-server)
(ws-server)
(repl-server)
; listen on the carbon protocol
(graphite-server :host "0.0.0.0"
:port 3003
:protocol :udp
; assume that all incoming carbon metrics have a name in the form
; ENV.GRID.CLUSTER.HOSTNAME.SERVICE
:parser-fn (fn [{:keys [service] :as event}]
              (if-let [[env grid cluster host metric]
                       (clojure.string/split service #"\.")]
                {:host host
                 :service metric
                 :environment env
                 :resource host
                 :grid grid
                 :cluster cluster
                 }))
)

(def graph
	(if (resolve 'local-testing)
		prn
		(graphite {
		:host hostname
		:path (fn [e] (str "riemann." (riemann.graphite/graphite-path-basic e)))
		})))

; reap expired events every 10 seconds
(periodically-expire 10)

; some helpful functions
(defn now []
		(Math/floor (unix-time)))

(defn switch-epoch-to-elapsed
	[& children]
	(fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn log-info
	[e]
	(info e))

; set of severity functions
(defn severity
	[severity message & children]
	(fn [e] ((apply with {:state severity :description message} children) e)))

(def informational (partial severity "informational"))
(def normal (partial severity "normal"))
(def warning (partial severity "warning"))
(def minor (partial severity "minor"))
(def major (partial severity "major"))
(def critical (partial severity "critical"))

(defn edge-detection
	[samples & children]
	(let [detector (by [:host :service] (runs samples :state (apply changed :state {:init "normal"} children)))]
		(fn [e] (detector e))))

; thresholding
(let [index (default :ttl 300 (update-index (index)))
		dedup-alert (edge-detection 1 log-info alerta)
		dedup-2-alert (edge-detection 2 log-info alerta)
		dedup-4-alert (edge-detection 4 log-info alerta)]
	(streams
		index)

	(streams
		(expired
			log-info))

	(streams
		(throttle 1 30 heartbeat))

	(streams
		(let [hosts (atom #{})]
			(fn [event]
				(swap! hosts conj (:host event))
				(index {:service "unique hosts"
						:time (unix-time)
						:metric (count @hosts)})
				((throttle 1 5 graph) {:service "riemann unique_hosts"
						:host hostname
						:time (unix-time)
						:metric (count @hosts)}))))

	(streams
		(let [metrics (atom #{})]
			(fn [event]
				(swap! metrics conj {:host (:host event) :service (:service event)})
				(index {:service "unique services"
						:time (unix-time)
						:metric (count @metrics)})
				((throttle 1 5 graph) {:service "riemann unique_services"
						:host hostname
						:time (unix-time)
						:metric (count @metrics)}))))

	(streams
		(match :service "boottime"
			(where* 
				(fn [e] 
					(let [boot-threshold (- (now) 7200)]
						(> (:metric e) boot-threshold)))
				(with {:event "SystemStart" :group "System"} 
					(informational "System started less than 2 hours ago" dedup-alert)))))

	(streams
		(match :service "heartbeat"
			(with {:event "GangliaHeartbeat" :group "Ganglia" :count 2}
				(splitp < metric
					90 (critical "No heartbeat from Ganglia agent for at least 90 seconds" dedup-alert)
					(normal "Heartbeat from Ganglia agent OK" dedup-alert)))))

	; TODO - GangliaTCPStatus - string based metric

	(streams
		(match :service "pup_last_run"
			(with {:event "PuppetLastRun" :group "Puppet"}
				(let [last-run-threshold (- (now) 7200)]
					(splitp > metric
						last-run-threshold
							(switch-epoch-to-elapsed
								(major "Puppet agent has not run for at least 2 hours" dedup-alert))
						(switch-epoch-to-elapsed
							(normal "Puppet agent is OK" dedup-alert)))))))

	(streams
		(match :service "pup_res_failed"
			(with {:event "PuppetResFailed" :group "Puppet"}
				(splitp < metric
					0 (warning "Puppet resources are failing" dedup-alert)
					(normal "Puppet is updating all resources" dedup-alert)))))

	(streams
		(match :service "gu_metric_last"
			(with {:event "GuMgmtMetrics" :group "Ganglia"}
				(let [last-run-threshold (- (now) 300)]
					(splitp > metric
						last-run-threshold
							(switch-epoch-to-elapsed
								(minor "Guardian management status metrics have not been updated for more than 5 minutes" dedup-alert))
						(switch-epoch-to-elapsed
							(normal "Guardian management status metrics are OK" dedup-alert)))))))

	(streams
		(match :service #"^fs_util-"
			(with {:event "FsUtil" :group "OS"}
				(splitp < metric
					95 (critical "File system utilisation is very high" dedup-alert)
					90 (major "File system utilisation is high" dedup-alert)
					(normal "File system utilisation is OK" dedup-alert)))))

	(streams
		(match :service #"^inode_util-"
			(with {:event "InodeUtil" :group "OS"}
				(splitp < metric
					95 (critical "File system inode utilisation is very high" dedup-alert)
					90 (major "File system inode utilisation is high" dedup-alert)
					(normal "File system inode utilisation is OK" dedup-alert)))))

	(streams
		(match :service "swap_util"
			(with {:event "SwapUtil" :group "OS"}
				(splitp < metric
					90 (minor "Swap utilisation is very high" dedup-alert)
					(normal "Swap utilisation is OK" dedup-alert)))))

	; TODO - LoadHigh - references two metrics (one static, so look up from index??)

	; TODO - SnapmirrorSync - ask nick what this is doing - seems to be comparing same metric to self

	(streams
		(match :service "df_percent-kb-capacity" ; TODO - in alerta config the split is disjoint
			(with {:event "VolumeUsage" :group "netapp"}
				(splitp < metric
					90 (critical "Volume utilisation is very high" dedup-alert)
					85 (major "Volume utilisation is high" dedup-alert)
					(normal "Volume utilisation is OK" dedup-alert)))))

	; TODO - R2CurrentMode - string based metric

	(streams
		(match :service "gu_requests_timing_time-r2frontend"
			(with {:event "ResponseTime" :group "Web"}
				(splitp < metric
					500 (minor "R2 response time is slow" dedup-4-alert)
					(normal "R2 response time is OK" dedup-4-alert)))))
	
	; TODO - ResponseTime - for cluster

	(streams
		(match :service "gu_database_calls_time-r2frontend"
			(with {:event "DbResponseTime" :group "Database"}
				(splitp < metric
					30 (minor "R2 database response time is slow" dedup-2-alert)
					(normal "R2 database response time is OK" dedup-2-alert)))))

	; TODO - check this - the alerta check seems non-sensical as it uses a static value	
	; (streams
	; 	(match :grid "Frontend"
	; 		(with {:event "NgnixError" :group "Web"}
	; 			(splitp < metric
	; 				0 (major "There are status code 499 client errors")
	; 				(normal "No status code 499 client errors")))))

	(streams
		(match :grid "Discussion"
			(match :service "gu_httprequests_application_time-DiscussionApi"
				(with {:event "ResponseTime" :group "Web"}
					(splitp < metric
						30 (minor "Discussion API response time is slow" dedup-2-alert)
						(normal "Discussion API response time is OK" dedup-2-alert))))))

	; TODO - this needs to be a cluster calculation - maybe a moving window???
	; (streams
	; 	(match :grid "EC2"
	; 		(match :environment "PROD"
	; 			(match :cluster "contentapimq_eu-west-1"
	; 				(match :service "gu_httprequests_application_time-DiscussionApi"
	; 					(with {:event "MQRequestRate" :group "Application"}
	; 						(splitp < metric
	; 							50 (normal "Content API MQ total request rate is OK")
	; 							(major "Content API MQ total request rate is low"))))))))

	; TODO - 


	(streams
		(with {:metric 1 :host hostname :state "normal" :service "riemann events_sec"}
			(rate 10 index graph)))

	(streams
		(by [:host]	
			(moving-time-window 15

				(smap (fn [events]
					(let [success-service-name "gu_200_ok_request_status_rate-frontend"
						error-service-name "gu_js_diagnostics_rate-frontend"
						service-filter (fn [service-name event] (and (:metric event) (= service-name (:service event))))
						sorted-events (->> events (sort-by :time) reverse)
						last-success (->> sorted-events (filter (partial service-filter success-service-name)) first)
						last-error (->> sorted-events (filter (partial service-filter error-service-name)) first)
						threshold 0.1]
						(if (and last-success last-error)
							(let [ratio (double (/ (:metric last-error) (+ (:metric last-success) (:metric last-error))))
									new-event {:host "riemann" :service "frontend_js_error_ratio" :metric ratio}]
								(do
									(info
										(format "Events seen %d; ratio %f; status %s"
											(count events)
											ratio
											(if (> ratio threshold) "bad" "okay")))
									(if (> ratio threshold)
										(call-rescue new-event (list (critical "JS error rate unexpectedly high" dedup-alert)))
										(call-rescue new-event (list (normal "JS error rate within limits" dedup-alert))))))))))))))


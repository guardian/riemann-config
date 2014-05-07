; -*- mode: clojure; -*-
; vim: filetype=clojure
(require '[clj-http.client :as client]
         '[cheshire.core :as json]
         '[riemann.query :as query])

(def hostname (.getHostName (java.net.InetAddress/getLocalHost)))

(include "alerta.clj")

; configure the various servers that we listen on
(tcp-server :host "0.0.0.0")
(udp-server :host "0.0.0.0")
(ws-server :host "0.0.0.0")
(repl-server)

(defn parse-stream
  [& children]
  (fn [e] (let [new-event (assoc e
                            :host (str (:ip e) ":" (:host e))
                            :resource (:host e))]
            (call-rescue new-event children))))

(defn log-info
  [e]
  (info e))


; reap expired events every 10 seconds
(periodically-expire 10 {:keep-keys [:host :service :environment :resource :grid :cluster :ip :tags :metric :index-time]})

; some helpful functions
(defn now []
		(Math/floor (unix-time)))

(defn switch-epoch-to-elapsed
  [& children]
  (fn [e] ((apply with {:metric (- (now) (:metric e))} children) e)))

(defn state-to-metric
  [& children]
  (fn [e] ((apply with {:metric (:state e)} children) e)))

(defn event-to-cluster-event
  [& children]
  (fn [e] ((apply with {:service "cluster heartbeat"
                        :host (str (:environment e) ":" (:cluster e))
                        :resource (:cluster e)} children) e)))

(defn event-to-grid-event
  [& children]
  (fn [e] ((apply with {:service "grid heartbeat"
                        :host (str (:environment e) ":" (:grid e))
                        :resource (:grid e)
                        :cluster "n/a"} children) e)))

(defn lookup-metric
  [metricname & children]
  (let [metricsymbol (keyword metricname)]
    (fn [e]
      (let [metricevent (.lookup (:index @core) (:host e) metricname)]
        (if-let [metricvalue (:metric metricevent)]
          (call-rescue (assoc e metricsymbol metricvalue) children))))))

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
  (let [detector (by [:host :service] (runs samples :state (apply changed :state children)))]
    (fn [e] (detector e))))

(defn set-resource-from-cluster [e] (assoc e :resource (:cluster e)))

; thresholding
(let [index (default :ttl 900 (index))
      alert (async-queue! :alerta {:queue-size 10000}
                          (alerta {}))
      dedup-alert (edge-detection 1 log-info alert)
      dedup-2-alert (edge-detection 2 log-info alert)
      dedup-4-alert (edge-detection 4 log-info alert)
      graph (async-queue! :graphite {:queue-size 1000}
                          (graphite {:host "graphite"
                                     :path (fn [e] (str "riemann." (riemann.graphite/graphite-path-basic e)))}))]

  (streams
    (with :index-time (format "%.0f" (now))
          (where (service "heartbeat")
                 (parse-stream
                   (with :ttl 300 index))
                 (event-to-cluster-event
                   (with {:event "ClusterHeartbeat" :group "Ganglia" :ttl 180}
                         (switch-epoch-to-elapsed
                           (where (< metric 20)
                                  (normal "Heartbeat from Ganglia cluster is OK" dedup-alert))) index))
                 (event-to-grid-event
                   (with {:event "GridHeartbeat" :group "Ganglia" :ttl 120}
                         (switch-epoch-to-elapsed
                           (where (< metric 20)
                                  (normal "Heartbeat from Ganglia grid is OK" dedup-alert))) index))
                 (else
                   index))))

  (streams
    (throttle 1 30 heartbeat))

;  (streams
;    (let [hosts (atom #{})]
;      (fn [event]
;        (swap! hosts conj (:host event))
;        (index {:service "unique hosts"
;                :time (unix-time)
;                :metric (count @hosts)})
;        ((throttle 1 10 graph) {:service "riemann unique_hosts"
;                                :host hostname
;                                :time (unix-time)
;                                :metric (count @hosts)}))))

;  (streams
;    (let [metrics (atom #{})]
;      (fn [event]
;        (swap! metrics conj {:host (:host event) :service (:service event)})
;        (index {:service "unique services"
;                :time (unix-time)
;                :metric (count @metrics)})
;        ((throttle 1 10 graph) {:service "riemann unique_services"
;                                :host hostname
;                                :time (unix-time)
;                                :metric (count @metrics)}))))

  (streams
    (expired
      (match :service "heartbeat"
             (with {:event "AgentHeartbeat" :group "Ganglia" }
                   (switch-epoch-to-elapsed
                     (minor "No heartbeat from Ganglia agent" dedup-alert) log-info)))
      (match :service "cluster heartbeat"
             (with {:event "ClusterHeartbeat" :group "Ganglia" }
                   (switch-epoch-to-elapsed
                     (major "No heartbeat from Ganglia cluster" dedup-alert) log-info)))
      (match :service "grid heartbeat"
             (with {:event "GridHeartbeat" :group "Ganglia" }
                   (switch-epoch-to-elapsed
                     (critical "No heartbeat from Ganglia grid" dedup-alert) log-info)))))

  (streams (parse-stream
             (let [boot-threshold
                   (match :service "boottime"
                          (with {:event "SystemStart" :group "System"}
                                (switch-epoch-to-elapsed
                                  (where (< metric 7200) (informational "System started less than 2 hours ago" dedup-alert)))))

                   heartbeat
                   (match :service "heartbeat"
                          (with {:event "AgentHeartbeat" :group "Ganglia"}
                                (switch-epoch-to-elapsed
                                  (splitp < metric
                                          90 (minor "Heartbeat from Ganglia agent is stale" dedup-alert)
                                          (normal "Heartbeat from Ganglia agent is OK" dedup-alert)))))

                   puppet-last-run
                   (match :service "pup_last_run"
                          (where (> metric 0)
                                 (with {:event "PuppetLastRun" :group "Puppet"}
                                       (switch-epoch-to-elapsed
                                         (splitp < metric
                                                 86400 (minor "Puppet has not run in more than a day" dedup-alert) ; not run in last day
                                                 7200 (warning "Puppet has not run in last 2 hours" dedup-alert)  ; not run in last 2 hours
                                                 (normal "Puppet agent is running normally" dedup-alert))))
                                 (else (with {:event "PuppetLastRun" :group "Puppet"}
                                             (warning "Puppet metrics are stale or broken" dedup-alert)))))

                   puppet-resource-failed
                   (match :service "pup_res_failed"
                          (with {:event "PuppetResFailed" :group "Puppet"}
                                (splitp < metric
                                        0 (warning "Puppet resources are failing" dedup-alert)
                                        (normal "Puppet is updating all resources" dedup-alert))))

                   last-gumetric-collection
                   (match :service "gu_metric_last"
                          (where (> metric 0)
                                 (with {:event "GuMgmtMetrics" :group "Ganglia"}
                                       (switch-epoch-to-elapsed
                                         (splitp < metric
                                                 300 (minor "Guardian management metrics not updated for 5 minutes or more" dedup-alert)
                                                 (normal "Guardian management metrics are reporting OK" dedup-alert))))
                                 (else (with {:event "GuMgmtMetrics" :group "Ganglia"}
                                             (warning "Guardian management metrics are stale or broken" dedup-alert)))))

                   disk-max-util
                   (match :service "part_max_used"
                          (with {:event "DiskMaxUtil" :group "OS"}
                                (splitp < metric
                                        99 (critical "Disk utilisation for highest filesystem over threshold" dedup-alert)
                                        (normal "Disk utilisation for highest filesystem is under threshold" dedup-alert))))

                   fs-util
                   (match :service #"^fs_util-"
                          (with {:event "FsUtil" :group "OS"}
                                (splitp < metric
                                        95 (major "File system utilisation is very high" dedup-alert)
                                        90 (minor "File system utilisation is high" dedup-alert)
                                        (normal "File system utilisation is OK" dedup-alert))))

                   inode-util
                   (match :service #"^inode_util-"
                          (with {:event "InodeUtil" :group "OS"}
                                (splitp < metric
                                        95 (major "File system inode utilisation is very high" dedup-alert)
                                        90 (minor "File system inode utilisation is high" dedup-alert)
                                        (normal "File system inode utilisation is OK" dedup-alert))))
                   swap-util
                   (match :service "swap_util"
                          (with {:event "SwapUtil" :group "OS"}
                                (splitp < metric
                                        90 (minor "Swap utilisation is very high" dedup-alert)
                                        (normal "Swap utilisation is OK" dedup-alert))))

                   cpu-load-five
                   (by [:host]
                       (match :service "load_five"
                              (with {:event "SystemLoad" :group "OS"}
                                    (lookup-metric "cpu_num"
                                                   (split*
                                                     (fn [e] (< (* 10 (:cpu_num e)) (:metric e))) (minor "System 5-minute load average is very high" dedup-alert)
                                                     (fn [e] (< (* 6 (:cpu_num e)) (:metric e))) (warning "System 5-minute load average is high" dedup-alert)
                                                     (normal "System 5-minute load average is OK" dedup-alert))))))

                   disk-io-util
                   (match :service #"^diskio_util-"
                          (with {:event "DiskIOUtil" :group "OS"}
                                (splitp < metric
                                        99 (major "Disk IO utilisation is very high" dedup-4-alert)
                                        95 (minor "Disk IO utilisation is high" dedup-4-alert)
                                        (normal "Disk IO utilisation is OK" dedup-4-alert))))

                   r2-frontend-mode
                   (match :service "gu_currentMode_mode-r2frontend"
                          (state-to-metric
                            (with {:event "R2Mode" :service "R2" :group "Application" :type "serviceAlert"}
                                  (where (= state "NORMAL")
                                         (normal "R2 frontend mode is OK" dedup-alert)
                                         (else (major "R2 frontend mode is not OK" dedup-alert))))))

                   mysql-slave-lag
                   (match :service "mysql_slave_lag"
                          (with {:event "MySQLlag" :group "MySQL"}
                                (splitp < metric
                                        14400 (major "MySQL Replication lag is very high" dedup-alert)
                                        7200 (minor "MySQL Replication lag is high" dedup-alert)
                                        (normal "MySQL Replication lag is OK" dedup-alert))))

                   content-api-host-item-request-time
                   (where* (fn [e] (and (= (:grid e) "EC2")
                                        (= (:environment e) "PROD")
                                        (= (:service e) "gu_item_http_time-Content-API")))
                           (with {:event "HostItemResponseTime" :group "Application" :grid "ContentAPI"}
                                 (by :resource
                                     (moving-time-window 300
                                                         (combine riemann.folds/mean
                                                                  (splitp < metric
                                                                          300 (minor "Content API host item response time is slow" dedup-alert)
                                                                          (normal "Content API host item response time is OK" dedup-alert)))))))

                   content-api-host-search-request-time
                   (where* (fn [e] (and (= (:grid e) "EC2")
                                        (= (:environment e) "PROD")
                                        (= (:service e) "gu_search_http_time-Content-API")))
                           (with {:event "HostSearchResponseTime" :group "Application" :grid "ContentAPI"}
                                 (by :resource
                                     (moving-time-window 300
                                                         (combine riemann.folds/mean
                                                                  (splitp < metric
                                                                          200 (minor "Content API host search response time is slow" dedup-alert)
                                                                          (normal "Content API host search response time is OK" dedup-alert)))))))

                   content-api-request-time
                   (where* (fn [e] (and (= (:grid e) "EC2")
                                        (= (:environment e) "PROD")
                                        (= (:cluster e) "contentapimq_eu-west-1")
                                        (= (:service e) "gu_httprequests_application_time-Content-API")))
                           (with {:event "ResponseTime" :group "Application" :grid "ContentAPI"}
                                 (by :cluster
                                     (moving-time-window 30
                                                         (combine riemann.folds/mean
                                                                  (adjust set-resource-from-cluster
                                                                          (splitp < metric
                                                                                  300 (minor "Content API MQ cluster response time is slow" dedup-2-alert)
                                                                                  (normal "Content API MQ cluster response time is OK" dedup-2-alert))))))))

                   content-api-request-rate
                   (where* (fn [e] (and (= (:grid e) "EC2")
                                        (= (:environment e) "PROD")
                                        (= (:cluster e) "contentapimq_eu-west-1")
                                        (= (:service e) "gu_httprequests_application_rate-Content-API")))
                           (with {:event "MQRequestRate" :group "Application" :grid "ContentAPI"}
                                 (by :cluster
                                     (fixed-time-window 15
                                                        (combine riemann.folds/sum
                                                                 (adjust set-resource-from-cluster
                                                                         (splitp < metric
                                                                                 70 (normal "Content API MQ total request rate is OK" dedup-2-alert)
                                                                                 (minor "Content API MQ total request rate is low" dedup-2-alert))))))))]

               (where (not (state "expired"))
                      ; prn
                      boot-threshold
                      heartbeat
                      disk-max-util
                      puppet-last-run
                      puppet-resource-failed
                      last-gumetric-collection
                      fs-util
                      inode-util
                      swap-util
                      cpu-load-five
                      disk-io-util

                      r2-frontend-mode

                      mysql-slave-lag

                      ;content-api-host-item-request-time
                      ;content-api-host-search-request-time
                      ;content-api-request-time
                      ;content-api-request-rate
))))

  (streams
    (with {:metric 1 :host hostname :state "normal" :service "riemann events_sec"}
          (rate 10 index graph))))

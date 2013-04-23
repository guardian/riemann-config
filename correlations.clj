(defn keys-equal? [target sample]
  (let [target-keys (keys target)]
    (every? true? (map (fn [k] (= (k target) (k sample))) target-keys))))

(defn ratio [m n]
  (float (if (< m n) (/ m n) (/ n m))))

(defn correlate [a b condition event-emitter & children]
  (let [last-a (atom nil) last-b (atom nil)
    tracked-metric? (fn [targets m] (some #(keys-equal? % m) targets))]
    (fn [e]
      (if (tracked-metric? [a b] e)
        (let [_ (if (tracked-metric? [a] e) (reset! last-a e) (reset! last-b e))
          [result value] (condition @last-a @last-b)]
          (if result (call-rescue (event-emitter e value @last-a @last-b) children)))))))

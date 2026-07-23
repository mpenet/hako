(ns quick
  "Fast bench for iterative perf work — hako encode/decode only, no
  peers, short criterium time. Run: clj -M:bench -m quick"
  (:require [criterium.core :as c]
            [s-exp.hako :as hako]))

(def payloads
  {:small-map {:a 1 :b 2 :c 3}
   :mixed [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]
   :nested-map (into {} (for [i (range 50)]
                          [(keyword (str "k" i))
                           {:idx i :val (* i i) :tag :leaf}]))
   :vec-of-longs (vec (range 1000))
   :long-array-1k (long-array (range 1000))})

;; Criterium quick-bench defaults to ~1s per bench. Cut to 250ms for
;; iterative work.
(def opts
  {:target-execution-time (long (* 250 1e6))
   :warmup-jit-period     (long (* 250 1e6))
   :samples               3})

(defn- fmt [seconds]
  (let [ns (* seconds 1e9)]
    (cond
      (< ns 1000)     (format "%7.1f ns" ns)
      (< ns 1000000)  (format "%7.2f µs" (/ ns 1000.0))
      :else           (format "%7.2f ms" (/ ns 1000000.0)))))

(defn- run [label f]
  (let [result (c/quick-benchmark* f opts)
        mean (first (:mean result))]
    (println (format "  %-4s %s" label (fmt mean)))
    mean))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label v] selected]
      (println "===" label "===")
      (let [enc-bs (hako/encode v)]
        (run "enc" (fn [] (hako/encode v)))
        (run "dec" (fn [] (hako/decode enc-bs)))))))

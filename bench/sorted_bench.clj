(ns sorted-bench
  "Measure encode cost + alloc for sorted-coll + queue payloads to size
  the win from a Java-native path."
  (:require [criterium.core :as c]
            [s-exp.hako :as hako])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(def payloads
  {:sorted-set-100 (into (sorted-set) (range 100))
   :sorted-map-100 (into (sorted-map) (map vector (range 100) (range 100)))
   :queue-100      (into (clojure.lang.PersistentQueue/EMPTY) (range 100))
   :hashmap-100    (into {} (map vector (range 100) (range 100)))})

(defn- ^ThreadMXBean tmx []
  (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes ^long [^ThreadMXBean b]
  (.getThreadAllocatedBytes b (.getId (Thread/currentThread))))

(defn- alloc-per-op [f iters]
  (dotimes [_ 5000] (f))
  (System/gc)
  (let [b (tmx)
        start (allocated-bytes b)
        _ (dotimes [_ iters] (f))
        end (allocated-bytes b)]
    (/ (double (- end start)) iters)))

(defn -main [& _]
  (let [wr (hako/writer 8192)
        iters 50000]
    (println "=== timing ===")
    (doseq [[label v] payloads]
      (println "\n==" label "==")
      (c/quick-bench (hako/encode-into! wr v)))
    (println "\n=== alloc ===")
    (println (format "%-20s %10s" "payload" "B/op"))
    (doseq [[label v] payloads]
      (println (format "%-20s %10.0f" (name label)
                       (alloc-per-op #(hako/encode-into! wr v) iters))))))

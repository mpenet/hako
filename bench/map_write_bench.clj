(ns map-write-bench
  "Measure alloc from Writer.writeMap iterator allocation."
  (:require [s-exp.hako :as hako])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(def payloads
  {:small-map      {:name "Alice" :age 30 :city "Paris"}
   :nested-map     (into {} (for [i (range 50)]
                              [(keyword (str "k" i))
                               {:idx i :val (* i i) :tag :leaf}]))
   :fat-map        (into {} (for [i (range 200)]
                              [(keyword (str "k" i)) i]))
   :set-100        (into #{} (map #(str "s-" %) (range 100)))
   :set-1k         (into #{} (range 1000))})

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
    (println (format "%-20s %12s" "payload" "B/op"))
    (println (apply str (repeat 34 "-")))
    (doseq [[label v] payloads]
      (println (format "%-20s %12.1f"
                       (name label)
                       (alloc-per-op #(hako/encode-into! wr v) iters))))))

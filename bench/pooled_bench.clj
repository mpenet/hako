(ns pooled-bench
  "Alloc + timing comparison: decode vs decode-pooled, encode vs encode-pooled."
  (:require [s-exp.hako :as hako])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

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

(def payloads
  {:tiny        42
   :small-map   {:name "Alice" :age 30 :city "Paris"}
   :nested-map  (into {} (for [i (range 50)]
                           [(keyword (str "k" i))
                            {:idx i :val (* i i)}]))
   :long-1k     (long-array (range 1000))})

(defn -main [& _]
  (let [iters 50000]
    (println (format "%-20s %-24s %12s" "payload" "op" "B/op"))
    (println (apply str (repeat 60 "-")))
    (doseq [[label v] payloads]
      (let [enc-normal (hako/encode v)]
        (println (format "%-20s %-24s %12.0f" (name label) "encode"
                         (alloc-per-op #(hako/encode v) iters)))
        (println (format "%-20s %-24s %12.0f" (name label) "encode-pooled"
                         (alloc-per-op #(hako/encode-pooled v) iters)))
        (println (format "%-20s %-24s %12.0f" (name label) "decode"
                         (alloc-per-op #(hako/decode enc-normal) iters)))
        (println (format "%-20s %-24s %12.0f" (name label) "decode-pooled"
                         (alloc-per-op #(hako/decode-pooled enc-normal) iters)))
        (println)))))

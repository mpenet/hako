(ns batch-alloc
  "Per-op alloc audit for hako's batch APIs: encode-many, decode-many,
  and the decoder reducible/iterable."
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
  {:kw-heavy-100    (repeat 100 {:name "Alice" :age 30 :active true})
   :mixed-100       (repeat 100 [1 :kw "str" {:a 1} #{:x :y}])
   :small-long-100  (repeat 100 42)
   :single-map      [{:name "Alice" :age 30 :city "Paris"}]})

(defn -main [& _]
  (let [iters 20000
        wr (hako/writer 8192)]
    (println (format "%-20s %-30s %12s"
                     "payload" "op" "B/op"))
    (println (apply str (repeat 64 "-")))
    (doseq [[label vs] payloads]
      (let [enc (hako/encode-many vs {:cache-idents true})]
        (println (format "%-20s %-30s %12.0f  (payload %d B)"
                         (name label) "encode-many"
                         (alloc-per-op #(hako/encode-many vs {:cache-idents true}) iters)
                         (alength enc)))
        (println (format "%-20s %-30s %12.0f"
                         (name label) "encode-many-into! (reused)"
                         (alloc-per-op #(hako/encode-many-into! wr vs {:cache-idents true}) iters)))
        (println (format "%-20s %-30s %12.0f"
                         (name label) "decode-many"
                         (alloc-per-op #(hako/decode-many enc {:cache-idents true}) iters)))
        (println (format "%-20s %-30s %12.0f"
                         (name label) "reduce over decoder"
                         (alloc-per-op #(reduce (fn [acc _] acc) 0 (hako/decoder enc {:cache-idents true})) iters)))
        (println)))))

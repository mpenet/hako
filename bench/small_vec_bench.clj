(ns small-vec-bench
  "Bench small-vec readVector — LazilyPersistentVector.createOwning vs transient."
  (:require [criterium.core :as c]
            [s-exp.hako :as hako])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(def payloads
  {:vec-3         [1 2 3]
   :vec-8         (vec (range 8))
   :vec-16        (vec (range 16))
   :vec-32        (vec (range 32))
   :vec-33        (vec (range 33))
   :vec-100       (vec (range 100))
   :nested-vecs   (vec (repeatedly 10 #(vec (range 8))))
   :vec-of-vec-8  (vec (repeatedly 100 #(vec (range 8))))})

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
  (let [rd (hako/reader (byte-array 0))
        iters 50000]
    (println (format "%-15s %10s %10s" "payload" "µs" "B/op"))
    (println (apply str (repeat 40 "-")))
    (doseq [[label v] payloads]
      (let [enc (hako/encode v)
            alloc (alloc-per-op #(hako/decode-into! rd enc {:cache-idents true}) iters)]
        (print (format "%-15s " (name label)))
        (let [t (with-out-str (c/quick-benchmark (hako/decode-into! rd enc {:cache-idents true}) {}))]
          (let [m (re-find #"Execution time mean : ([\d.]+ [µn]s)" t)]
            (print (format "%10s " (second m)))))
        (println (format "%10.1f" alloc))))))

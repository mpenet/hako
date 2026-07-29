(ns seq-bench
  "Bench ISeq/Iterable encode paths — task #50 impact.
  Compare lazy seq (writeSeqAny) vs vector (writeVecAny baseline)
  vs Java ArrayList (writeIterableAny Collection fast path)."
  (:require [criterium.core :as c]
            [s-exp.hako :as hako])
  (:import (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)
           (java.util ArrayList)))

(def n 1000)

(defn payloads []
  {:lazy-seq-1k       (map inc (range n))
   :list-1k           (apply list (range n))
   :vec-1k            (vec (range n))
   :arraylist-1k      (ArrayList. ^java.util.Collection (range n))
   :nested-lazy       (map (fn [_] (map inc (range 10))) (range 100))
   :nested-vec        (vec (map (fn [_] (vec (range 10))) (range 100)))})

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

(defn -main [& args]
  (let [wr (hako/writer 8192)
        iters 50000]
    (println "\n=== TIMING (encode-into! reused writer) ===")
    (doseq [[label p] (payloads)]
      (println "\n==" label "==")
      (c/quick-bench (hako/encode-into! wr p)))
    (println "\n=== ALLOC per op (B/op) ===")
    (doseq [[label p] (payloads)]
      (let [bpo (alloc-per-op #(hako/encode-into! wr p) iters)]
        (println (format "  %-16s %10.1f B/op" (name label) bpo))))))

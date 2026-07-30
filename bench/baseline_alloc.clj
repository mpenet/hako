(ns baseline-alloc
  "Measure per-op alloc of encode-into! variants to identify 40 B baseline source."
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)
           (com.sun.management ThreadMXBean)
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

(defn -main [& _]
  (let [wr ^Writer (hako/writer 4096)
        arr (long-array 100)
        iters 100000]
    ;; Priming the writer to install the handler
    (hako/encode-into! wr arr)
    (println "=== Isolating 40 B baseline ===")
    (println (format "full encode-into!      : %5.1f B" (alloc-per-op #(hako/encode-into! wr arr) iters)))
    (println (format "encode-into! sans final: %5.1f B"
                     (alloc-per-op #(do (.reset wr)
                                        (.setWriteMeta wr false)
                                        (.setPackHomogeneous wr false)
                                        (.setCoerceCustomComparator wr false)
                                        (.writeEnvelope wr)
                                        (.writeAny wr arr)
                                        ;; skip .finish
                                        )
                                   iters)))
    (println (format "only .finish()         : %5.1f B" (alloc-per-op #(.finish wr) iters)))
    (println (format "no-op                  : %5.1f B" (alloc-per-op #(do) iters)))))

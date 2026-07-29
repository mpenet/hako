(ns java-records-bench
  "Bench Java-record path — task #51 impact.
  Uses Point (int, int). Java record path uses MethodHandle accessors."
  (:require [criterium.core :as c]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako.testing Point)
           (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)))

(ext/register-record! Point)

(def payload (vec (for [i (range 1000)] (Point. i (inc i)))))
(def single  (Point. 42 43))

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
    (println "\n=== TIMING (encode-into! reused writer) ===")
    (println "\n== single Point ==")
    (c/quick-bench (hako/encode-into! wr single))
    (println "\n== 1000 x Point ==")
    (c/quick-bench (hako/encode-into! wr payload))
    (println "\n=== ALLOC (B/op) ===")
    (println (format "  single-point   %10.1f" (alloc-per-op #(hako/encode-into! wr single) iters)))
    (println (format "  1000-points    %10.1f" (alloc-per-op #(hako/encode-into! wr payload) iters)))))

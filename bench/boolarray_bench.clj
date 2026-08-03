(ns boolarray-bench
  "Scoped bench for boolean[] encode — per-element seg.set vs
  scratch-buffer bulk copy. Run: clj -M:bench -m boolarray-bench"
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)))

(def payloads
  {:bools-64   (boolean-array (map even? (range 64)))
   :bools-1k   (boolean-array (map even? (range 1000)))
   :bools-100k (boolean-array (map even? (range 100000)))})

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)]
    (dotimes [_ iters] (f))
    (/ (- (System/nanoTime) t0) (double iters))))

(defn -main [& _]
  (let [wr ^Writer (hako/writer (* 1024 256))]
    (println (format "%-12s %12s" "payload" "ns/op"))
    (doseq [[label v] payloads]
      (let [iters (if (= label :bools-100k) 5000 50000)
            ns-op (bench-one #(hako/encode-into! wr v) iters (quot iters 5))]
        (println (format "%-12s %12.1f" (name label) ns-op))))))

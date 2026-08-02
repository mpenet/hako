(ns pack-bench
  "Scoped bench for :pack-homogeneous vector encoding — success paths
  (all-Long / all-Double) plus mismatch controls (bail early / bail at
  tail, the worst case for optimistic single-pass fill).
  Run: clj -M:bench -m pack-bench"
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)))

(def payloads
  {:longs-1k      (vec (range 1000))
   :doubles-1k    (mapv double (range 1000))
   :mismatch-head (into [1 :kw] (range 998))
   :mismatch-tail (conj (vec (range 999)) :kw)})

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)
        _  (dotimes [_ iters] (f))
        t1 (System/nanoTime)]
    (/ (- t1 t0) (double iters))))

(defn -main [& _]
  (let [wr ^Writer (hako/writer 65536)
        opts {:pack-homogeneous true}]
    (println (format "%-14s %12s" "payload" "ns/op"))
    (println (apply str (repeat 28 "-")))
    (doseq [[label v] payloads]
      (let [ns-op (bench-one #(hako/encode-into! wr v opts) 50000 10000)]
        (println (format "%-14s %12.1f" (name label) ns-op))))))

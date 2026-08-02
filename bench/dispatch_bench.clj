(ns dispatch-bench
  "Scoped bench for writeAnyInner dispatch order — payloads dominated by
  boxed scalars (Boolean/Double/Integer) plus collection-heavy controls
  to catch regressions from the extra scalar checks on that path.
  Run: clj -M:bench -m dispatch-bench"
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)))

(def payloads
  {:vec-doubles  (mapv double (range 1000))
   :vec-booleans (vec (take 1000 (cycle [true false])))
   :vec-ints     (mapv int (range 1000))
   :vec-longs    (vec (range 1000))          ; control: hits first branch
   :nested-map   (into {} (for [i (range 50)] ; control: collection-heavy
                            [(keyword (str "k" i))
                             {:idx i :active (even? i) :score (double i)}]))})

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)
        _  (dotimes [_ iters] (f))
        t1 (System/nanoTime)]
    (/ (- t1 t0) (double iters))))

(defn -main [& _]
  (let [wr ^Writer (hako/writer 65536)]
    (println (format "%-14s %12s" "payload" "ns/op"))
    (println (apply str (repeat 28 "-")))
    (doseq [[label v] payloads]
      (let [ns-op (bench-one #(hako/encode-into! wr v) 50000 10000)]
        (println (format "%-14s %12.1f" (name label) ns-op))))))

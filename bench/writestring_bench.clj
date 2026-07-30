(ns writestring-bench
  "Fast micro-bench for writeString paths. Skips criterium overhead."
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)))

(def payloads
  {:small-map      {:name "Alice" :age 30 :city "Paris"}
   :mixed          [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]
   :vec-of-strings (vec (map #(str "item-" %) (range 100)))
   :string-100     (apply str (repeat 100 "x"))
   :string-10k     (apply str (repeat 10000 "x"))})

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)
        _  (dotimes [_ iters] (f))
        t1 (System/nanoTime)]
    (/ (- t1 t0) (double iters))))

(defn -main [& _]
  (let [wr ^Writer (hako/writer 8192)]
    (println (format "%-20s %12s"
                     "payload" "ns/op"))
    (println (apply str (repeat 34 "-")))
    (doseq [[label v] payloads]
      (let [ns (bench-one #(hako/encode-into! wr v) 200000 20000)]
        (println (format "%-20s %12.1f" (name label) ns))))))

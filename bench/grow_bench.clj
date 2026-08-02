(ns grow-bench
  "A/B bench for the Writer.grow path. Encodes payloads that force
  0 / ~6 / ~10 buffer doublings from a small initial buffer, with a
  fresh Writer per op so grow cost sits in the hot path.
  Run: clj -M:bench -m grow-bench"
  (:require [s-exp.hako :as hako]))

(def payloads
  ;; :initial-size 64 → doublings to fit payload
  {:no-grow    {:v (vec (range 4)) :init 4096}          ; 0 grows
   :grow-6     {:v (vec (range 1000)) :init 64}         ; ~8 KB, ~7 grows
   :grow-10    {:v (vec (range 20000)) :init 64}        ; ~160 KB, ~11 grows
   :grow-str   {:v (apply str (repeat 100000 "x")) :init 64}}) ; 100 KB string

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)
        _  (dotimes [_ iters] (f))
        t1 (System/nanoTime)]
    (/ (- t1 t0) (double iters))))

(defn -main [& _]
  (println (format "%-12s %12s" "payload" "ns/op"))
  (println (apply str (repeat 26 "-")))
  (doseq [[label {:keys [v init]}] payloads]
    (let [ns-op (bench-one #(hako/encode v {:initial-size init})
                           20000 5000)]
      (println (format "%-12s %12.1f" (name label) ns-op)))))

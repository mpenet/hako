(ns usertag-bench
  "Scoped bench for the user-tag encode/decode hot path — registry
  lookup cost per value (Clojure-side handler dispatch).
  Run: clj -M:bench -m usertag-bench"
  (:require [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako Reader Writer)
           (java.net URI)))

(ext/register-user-tag!
 7
 URI
 (fn write-uri [^Writer w ^URI u]
   (.writeString w (str u)))
 (fn read-uri [^Reader r]
   (let [tag-byte (.getByte r)
         low (bit-and tag-byte 0x0F)
         payload-len (.readTierPayload r (int low))]
     (URI. (.getString r (int payload-len))))))

(def payloads
  {:uri-1        (URI. "https://example.com/x")
   :vec-uris-100 (mapv #(URI. (str "https://example.com/" %)) (range 100))})

(defn- bench-one [f iters warm]
  (dotimes [_ warm] (f))
  (System/gc)
  (let [t0 (System/nanoTime)
        _  (dotimes [_ iters] (f))
        t1 (System/nanoTime)]
    (/ (- t1 t0) (double iters))))

(defn -main [& _]
  (let [wr ^Writer (hako/writer 65536)]
    (println (format "%-16s %12s %12s" "payload" "enc ns/op" "dec ns/op"))
    (println (apply str (repeat 42 "-")))
    (doseq [[label v] payloads]
      (let [enc (hako/encode v)
            e (bench-one #(hako/encode-into! wr v) 50000 10000)
            d (bench-one #(hako/decode enc) 50000 10000)]
        (println (format "%-16s %12.1f %12.1f" (name label) e d))))))

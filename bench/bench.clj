(ns bench
  "Criterium benchmarks: hako vs Nippy (default + fast) vs Deed vs Transit.

  Notes:
  - Nippy has two variants: `freeze` (default; includes compression + checksums)
    and `fast-freeze` (skips those). Both are shown.
  - Transit is a different niche (JSON-shaped, cross-language) — included as
    a size / speed reference.

  Run: clj -M:bench -m bench [payload-label ...]"
  (:require [cognitect.transit :as transit]
            [criterium.core :as c]
            [deed.core :as deed]
            [s-exp.hako :as hako]
            [taoensso.nippy :as nippy])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)))

(def payloads
  {:small-map {:name "Alice" :age 30 :city "Paris"}
   :vec-of-longs (vec (range 1000))
   :vec-of-strings (vec (map #(str "item-" %) (range 100)))
   :nested-map (into {} (for [i (range 50)]
                          [(keyword (str "k" i))
                           {:idx i :val (* i i) :tag :leaf}]))
   :long-array-1k (long-array (range 1000))
   :double-array-1k (double-array (range 1000))
   :string-100 (apply str (repeat 100 "x"))
   :string-10k (apply str (repeat 10000 "x"))
   :mixed [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]})

(defn- transit-encode ^bytes [payload]
  (let [baos (ByteArrayOutputStream. 512)
        w (transit/writer baos :msgpack)]
    (transit/write w payload)
    (.toByteArray baos)))

(defn- transit-decode [^bytes bs]
  (let [bais (ByteArrayInputStream. bs)
        r (transit/reader bais :msgpack)]
    (transit/read r)))

(defn- safe [f payload]
  (try (f payload) (catch Exception _ nil)))

(defn- bench-one [label payload]
  (println "===" label "===")
  (let [hako-enc       (hako/encode payload)
        nippy-enc      (nippy/freeze payload)
        nippy-fast-enc (nippy/fast-freeze payload)
        deed-enc       (deed/encode-to-bytes payload)
        transit-enc    (safe transit-encode payload)]
    (println "  size  — hako:" (alength hako-enc)
             " nippy:" (alength nippy-enc)
             " nippy-fast:" (alength nippy-fast-enc)
             " deed:" (alength deed-enc)
             " transit:" (if transit-enc (alength transit-enc) "n/a"))
    (println "  hako encode:")       (c/quick-bench (hako/encode payload))
    (println "  nippy encode:")      (c/quick-bench (nippy/freeze payload))
    (println "  nippy-fast encode:") (c/quick-bench (nippy/fast-freeze payload))
    (println "  deed encode:")       (c/quick-bench (deed/encode-to-bytes payload))
    (when transit-enc
      (println "  transit encode:")  (c/quick-bench (transit-encode payload)))
    (println "  hako decode:")       (c/quick-bench (hako/decode hako-enc {:cache-idents true}))
    (println "  nippy decode:")      (c/quick-bench (nippy/thaw nippy-enc))
    (println "  nippy-fast decode:") (c/quick-bench (nippy/fast-thaw nippy-fast-enc))
    (println "  deed decode:")       (c/quick-bench (deed/decode-from deed-enc))
    (when transit-enc
      (println "  transit decode:")  (c/quick-bench (transit-decode transit-enc)))))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label payload] selected]
      (bench-one label payload)
      (println))))

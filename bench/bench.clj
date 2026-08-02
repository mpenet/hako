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
  (:import (com.s_exp.hako Reader Writer)
           (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.lang.foreign Arena MemorySegment ValueLayout)))

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

;; criterium quick-bench spends 5s of JIT warmup per call — ~80% of its
;; runtime. Every library fn here is benched 9 payloads × several
;; variants in one process, so the code is C2-hot after the first
;; payload; 1.5s re-stabilizes the fresh per-call lambda. Sample count
;; and per-sample execution time (what the statistics actually depend
;; on) match quick-bench defaults.
(def ^:private bench-opts
  {:warmup-jit-period (long (* 1.5 1e9))
   :samples 6
   :target-execution-time (long (* 100 1e6))
   :tail-quantile 0.025
   :bootstrap-size 500})

(defmacro ^:private qb [expr]
  `(c/report-result (c/quick-benchmark ~expr bench-opts)))

(defn- seg->bytes ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(defn- bench-one [label payload]
  (println "===" label "===")
  (let [hako-enc       (hako/encode payload)
        hako-seg       (MemorySegment/ofArray hako-enc)
        nippy-enc      (nippy/freeze payload)
        nippy-fast-enc (nippy/fast-freeze payload)
        deed-enc       (deed/encode-to-bytes payload)
        transit-enc    (safe transit-encode payload)
        reused-wr      (hako/writer 4096)
        reused-rd      (hako/reader hako-enc)]
    (println "  size  — hako:" (alength hako-enc)
             " nippy:" (alength nippy-enc)
             " nippy-fast:" (alength nippy-fast-enc)
             " deed:" (alength deed-enc)
             " transit:" (if transit-enc (alength transit-enc) "n/a"))
    (println "  hako encode (→byte[]):")     (qb (hako/encode payload))
    (println "  hako encode-to-seg (per-call arena):")
    (qb (with-open [a (Arena/ofConfined)] (hako/encode-to-segment a payload)))
    (println "  hako⤾ encode-into! (→segment, reused):")
    (qb (hako/encode-into! reused-wr payload))
    (println "  hako⤾ encode→byte[] (reused + copy):")
    (qb (seg->bytes (hako/encode-into! reused-wr payload)))
    (println "  nippy encode:")       (qb (nippy/freeze payload))
    (println "  nippy-fast encode:")  (qb (nippy/fast-freeze payload))
    (println "  deed encode:")        (qb (deed/encode-to-bytes payload))
    (when transit-enc
      (println "  transit encode:")   (qb (transit-encode payload)))
    (println "  hako decode (byte[] source):")
    (qb (hako/decode hako-enc {:cache-idents true}))
    (println "  hako⤾ decode-into! (segment source, reused):")
    (qb (hako/decode-into! reused-rd hako-seg {:cache-idents true}))
    (println "  nippy decode:")       (qb (nippy/thaw nippy-enc))
    (println "  nippy-fast decode:")  (qb (nippy/fast-thaw nippy-fast-enc))
    (println "  deed decode:")        (qb (deed/decode-from deed-enc))
    (when transit-enc
      (println "  transit decode:")   (qb (transit-decode transit-enc)))
    (.close reused-wr)))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label payload] selected]
      (bench-one label payload)
      (println))))

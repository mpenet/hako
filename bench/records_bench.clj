(ns records-bench
  "Bench a vector of 100 records — hako vs Nippy.

  hako amortizes the record's classname + field-keyword payloads
  across the vector via the per-message symbol table (1-byte
  symrefs after first occurrence). Nippy re-emits each field name
  per record. The gap widens as vector length grows.

  Run: clj -M:bench -m records-bench"
  (:require [criterium.core :as c]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext]
            [taoensso.nippy :as nippy])
  (:import (com.s_exp.hako Writer)
           (java.lang.foreign MemorySegment ValueLayout)))

(defrecord Event [id ts user action payload])

(ext/register-record! Event)

(def payload
  (vec (for [i (range 100)]
         (->Event i
                  (+ 1700000000000 i)
                  (str "user-" (mod i 10))
                  (rand-nth [:click :view :scroll :hover])
                  {:score (* i i) :active (odd? i)}))))

(defn- seg->bytes ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(defn -main [& _]
  (let [hako-enc (hako/encode payload)
        nippy-enc (nippy/freeze payload)
        nippy-fast-enc (nippy/fast-freeze payload)
        wr (hako/writer 4096)
        rd (hako/reader hako-enc)]
    (println "=== 100 x Event record ===")
    (println "  size  — hako:" (alength hako-enc)
             " nippy:" (alength nippy-enc)
             " nippy-fast:" (alength nippy-fast-enc))
    (println "  hako encode (→byte[]):")     (c/quick-bench (hako/encode payload))
    (println "  hako-seg encode-into!:")     (c/quick-bench (hako/encode-into! wr payload))
    (println "  hako-seg encode→byte[]:")    (c/quick-bench (seg->bytes (hako/encode-into! wr payload)))
    (println "  nippy encode:")              (c/quick-bench (nippy/freeze payload))
    (println "  nippy-fast encode:")         (c/quick-bench (nippy/fast-freeze payload))
    (println "  hako decode (byte[]):")      (c/quick-bench (hako/decode hako-enc {:cache-idents true}))
    (println "  hako-seg decode-into!:")     (c/quick-bench (hako/decode-into! rd hako-enc {:cache-idents true}))
    (println "  nippy decode:")              (c/quick-bench (nippy/thaw nippy-enc))
    (println "  nippy-fast decode:")         (c/quick-bench (nippy/fast-thaw nippy-fast-enc))
    (.close wr)))

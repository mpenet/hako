(ns s-exp.hako
  "Public API for hako — schemaless, low-alloc Clojure serialization."
  (:require [s-exp.hako.reader :as r]
            [s-exp.hako.writer :as w])
  (:import (com.s_exp.hako Reader Writer)
           (java.lang.foreign Arena MemorySegment ValueLayout)))

(set! *warn-on-reflection* true)

(defn encode
  "Encode `value` and return a fresh byte[].

  For arena-backed zero-copy output use `encode-to-segment`.

  Options:
    :initial-size       — starting buffer size in bytes (default 256).
    :meta?              — preserve metadata on collections / IObjs (default false).
    :pack-homogeneous?  — detect all-Long / all-Double vectors and emit
                          them as packed prim arrays (default false)."
  (^bytes [value] (encode value nil))
  (^bytes [value opts]
   (let [initial (long (or (:initial-size opts) 256))
         wr (Writer. initial)]
     (try
       (.setWriteMeta wr (boolean (:meta? opts)))
       (.setPackHomogeneous wr (boolean (:pack-homogeneous? opts)))
       (w/install-handler! wr)
       (.writeEnvelope wr)
       (.writeAny wr value)
       (let [seg (.finish wr)
             n (.byteSize seg)
             arr (byte-array n)]
         (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
         arr)
       (finally (.close wr))))))

(defn encode-to-segment
  "Encode `value` into a MemorySegment owned by `arena`. Returns the segment."
  (^MemorySegment [^Arena arena value]
   (encode-to-segment arena value nil))
  (^MemorySegment [^Arena arena value opts]
   (let [initial (long (or (:initial-size opts) 256))
         wr (Writer. initial)]
     (try
       (.setWriteMeta wr (boolean (:meta? opts)))
       (.setPackHomogeneous wr (boolean (:pack-homogeneous? opts)))
       (w/install-handler! wr)
       (.writeEnvelope wr)
       (.writeAny wr value)
       (let [src (.finish wr)
             n (.byteSize src)
             dst (.allocate arena n 1)]
         (MemorySegment/copy src 0 dst 0 n)
         dst)
       (finally (.close wr))))))

(defn writer
  "Allocate a reusable Writer with an initial buffer of `initial-size`
  bytes (default 4096). Close via `.close` when done. Use `encode-into!`
  for each message; the writer's arena is retained between calls."
  (^Writer [] (writer 4096))
  (^Writer [^long initial-size] (Writer. initial-size)))

(defn encode-into!
  "Encode `value` using the reusable `wr`. Returns a MemorySegment slice
  covering the encoded bytes. The slice is valid until the next
  `encode-into!` call on this writer, or `close`."
  (^MemorySegment [^Writer wr value] (encode-into! wr value nil))
  (^MemorySegment [^Writer wr value opts]
   (.reset wr)
   (.setWriteMeta wr (boolean (:meta? opts)))
   (.setPackHomogeneous wr (boolean (:pack-homogeneous? opts)))
   (w/install-handler! wr)
   (.writeEnvelope wr)
   (.writeAny wr value)
   (.finish wr)))

(defn reader
  "Allocate a reusable Reader bound to `src` (byte[] or MemorySegment).
  Rebind via `.reset(newSeg)`. Not thread-safe."
  ^Reader [src]
  (let [seg (cond
              (instance? MemorySegment src) src
              (bytes? src) (MemorySegment/ofArray ^bytes src)
              :else (throw (ex-info "hako: unsupported source"
                                    {:type (class src)})))
        rd (Reader. seg)]
    (r/configure! rd)
    rd))

(defn decode-into!
  "Decode a value using the reusable Reader `rd` rebound to `src`.
  Rebinds via `.reset` on each call."
  ([^Reader rd src] (decode-into! rd src nil))
  ([^Reader rd src opts]
   (let [seg (cond
               (instance? MemorySegment src) src
               (bytes? src) (MemorySegment/ofArray ^bytes src)
               :else (throw (ex-info "hako: unsupported source"
                                     {:type (class src)})))]
     (.reset rd seg)
     (r/configure! rd)
     (.setZeroCopy rd (boolean (:zero-copy? opts)))
     (.setTolerant rd (boolean (:tolerant? opts)))
     (.setCacheIdents rd (boolean (:cache-idents? opts)))
     (.readEnvelope rd)
     (.readAny rd))))

(defn decode
  "Decode a hako-format value from `src` — a byte[] or a MemorySegment.

  Options:
    :zero-copy? — return MemorySegment slices for byte payloads instead of
                  copying to byte[] (default false). Slices are valid only
                  while the source segment / arena remain alive.
    :tolerant?  — unknown user-tag ids resolve to TaggedValue rather than
                  throwing (default false).
    :cache-idents? — consult a JVM-global cache when interning decoded
                  keywords / symbols. Wins on keyword-heavy payloads
                  (~2x on 50+ unique kw), slight overhead on tiny maps
                  (default false)."
  ([src] (decode src nil))
  ([src opts]
   (let [seg (cond
               (instance? MemorySegment src) src
               (bytes? src) (MemorySegment/ofArray ^bytes src)
               :else (throw (ex-info "hako: unsupported source"
                                     {:type (class src)})))
         rd (Reader. seg)]
     (r/configure! rd)
     (.setZeroCopy rd (boolean (:zero-copy? opts)))
     (.setTolerant rd (boolean (:tolerant? opts)))
     (.setCacheIdents rd (boolean (:cache-idents? opts)))
     (.readEnvelope rd)
     (.readAny rd))))

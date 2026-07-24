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
    :initial-size                — starting buffer size in bytes (default 256).
    :preserve-meta                       — preserve metadata on collections / IObjs (default false).
    :pack-homogeneous           — detect all-Long / all-Double vectors and emit
                                   them as packed prim arrays (default false).
    :coerce-custom-comparator   — allow encoding sorted-set-by / sorted-map-by
                                   as default-cmp sorted collections. Comparator
                                   is lost on decode. Warns once per JVM."
  (^bytes [value] (encode value nil))
  (^bytes [value opts]
   (let [initial (long (or (:initial-size opts) 256))
         wr (Writer/forHeap initial)]
     (try
       (.setWriteMeta wr (boolean (:preserve-meta opts)))
       (.setPackHomogeneous wr (boolean (:pack-homogeneous opts)))
       (.setCoerceCustomComparator wr (boolean (:coerce-custom-comparator opts)))
       (w/install-handler! wr)
       (.writeEnvelope wr)
       (.writeAny wr value)
       (.finishBytes wr)
       (finally (.close wr))))))

(defn encode-to-segment
  "Encode `value` into a MemorySegment owned by `arena`. Returns the segment."
  (^MemorySegment [^Arena arena value]
   (encode-to-segment arena value nil))
  (^MemorySegment [^Arena arena value opts]
   (let [initial (long (or (:initial-size opts) 256))
         wr (Writer. initial)]
     (try
       (.setWriteMeta wr (boolean (:preserve-meta opts)))
       (.setPackHomogeneous wr (boolean (:pack-homogeneous opts)))
       (.setCoerceCustomComparator wr (boolean (:coerce-custom-comparator opts)))
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
  (^Writer [^long initial-size]
   (let [wr (Writer. initial-size)]
     (w/install-handler! wr)
     wr)))

(defn encode-into!
  "Encode `value` using the reusable `wr`. Returns a MemorySegment slice
  covering the encoded bytes. The slice is valid until the next
  `encode-into!` call on this writer, or `close`.

  The Writer must have had `w/install-handler!` called at least once
  (the `writer` fn does this). `.reset` preserves the handler."
  (^MemorySegment [^Writer wr value] (encode-into! wr value nil))
  (^MemorySegment [^Writer wr value opts]
   (.reset wr)
   (.setWriteMeta wr (boolean (:preserve-meta opts)))
   (.setPackHomogeneous wr (boolean (:pack-homogeneous opts)))
   (.setCoerceCustomComparator wr (boolean (:coerce-custom-comparator opts)))
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
  Rebinds via `.reset` on each call.

  The Reader must have been produced by the `reader` fn (or otherwise
  have `r/configure!` called on it); `.reset` preserves the extension
  handler and array-map thresholds."
  ([^Reader rd src] (decode-into! rd src nil))
  ([^Reader rd src opts]
   (let [seg (cond
               (instance? MemorySegment src) src
               (bytes? src) (MemorySegment/ofArray ^bytes src)
               :else (throw (ex-info "hako: unsupported source"
                                     {:type (class src)})))]
     (.reset rd seg)
     (.setZeroCopy rd (boolean (:zero-copy opts)))
     (.setTolerant rd (boolean (:tolerate-unknown-tags opts)))
     (.setCacheIdents rd (boolean (:cache-idents opts)))
     (.readEnvelope rd)
     (.readAny rd))))

(defn encode-many
  "Encode `values` (a sequence) into a single byte[] sharing one symbol
  table across the whole stream. Reads compact when many values repeat
  the same keyword / symbol identifiers.

  The output has one envelope prefix followed by concatenated encoded
  values. Use `decode-many` to read back."
  (^bytes [values] (encode-many values nil))
  (^bytes [values opts]
   (let [initial (long (or (:initial-size opts) 4096))
         wr (Writer/forHeap initial)]
     (try
       (.setWriteMeta wr (boolean (:preserve-meta opts)))
       (.setPackHomogeneous wr (boolean (:pack-homogeneous opts)))
       (.setCoerceCustomComparator wr (boolean (:coerce-custom-comparator opts)))
       (w/install-handler! wr)
       (.writeEnvelope wr)
       (doseq [v values] (.writeAny wr v))
       (.finishBytes wr)
       (finally (.close wr))))))

(defn- ^MemorySegment ->segment [src]
  (cond
    (instance? MemorySegment src) src
    (bytes? src) (MemorySegment/ofArray ^bytes src)
    :else (throw (ex-info "hako: unsupported source"
                          {:type (class src)}))))

(defn- fresh-reader ^Reader [^MemorySegment seg opts]
  (let [rd (Reader. seg)]
    (r/configure! rd)
    (.setZeroCopy rd (boolean (:zero-copy opts)))
    (.setTolerant rd (boolean (:tolerate-unknown-tags opts)))
    (.setCacheIdents rd (boolean (:cache-idents opts)))
    (.readEnvelope rd)
    rd))

(defn decoder
  "Return a reducible + iterable source over all values in `src`
  (a byte[] or MemorySegment). Terminates cleanly on both stream
  outputs from `encode-many` and single-value outputs from `encode`.

  Compose with standard Clojure fns:

    (into #{} (filter :active) (hako/decoder bs))
    (sequence xform (hako/decoder bs))
    (eduction  xform (hako/decoder bs))
    (reduce f init (hako/decoder bs))
    (transduce xform f init (hako/decoder bs))
    (run! process! (hako/decoder bs))

  Each reduction / iteration spins up a fresh Reader — the source is
  safe to walk multiple times, and early termination via `reduced` or
  a short `.hasNext` stops the reader immediately without over-reading.

  Distinct from `reader`: `reader` returns a mutable Reader instance
  (for `decode-into!`); `decoder` returns a lazy value-stream source.

  Options — same as `decode`: `:zero-copy`, `:tolerate-unknown-tags`,
  `:cache-idents`."
  ([src] (decoder src nil))
  ([src opts]
   (let [seg (->segment src)]
     (reify
       clojure.lang.IReduceInit
       (reduce [_ f init]
         (let [rd (fresh-reader seg opts)]
           (loop [acc init]
             (if (zero? (.remaining rd))
               acc
               (let [acc' (f acc (.readAny rd))]
                 (if (reduced? acc')
                   @acc'
                   (recur acc')))))))
       Iterable
       (iterator [_]
         (let [rd (fresh-reader seg opts)]
           (reify java.util.Iterator
             (hasNext [_] (pos? (.remaining rd)))
             (next [_] (.readAny rd))
             (remove [_] (throw (UnsupportedOperationException.))))))))))

(defn decode-many
  "Convenience wrapper. Equivalent to `(into [] (decoder src opts))`."
  ([src] (decode-many src nil))
  ([src opts] (into [] (decoder src opts))))

(defn decode
  "Decode a hako-format value from `src` — a byte[] or a MemorySegment.

  Options:
    :zero-copy — return MemorySegment slices for byte payloads instead of
                  copying to byte[] (default false). Slices are valid only
                  while the source segment / arena remain alive.
    :tolerate-unknown-tags  — unknown user-tag ids resolve to TaggedValue rather than
                  throwing (default false).
    :cache-idents — consult a JVM-global cache when interning decoded
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
     (.setZeroCopy rd (boolean (:zero-copy opts)))
     (.setTolerant rd (boolean (:tolerate-unknown-tags opts)))
     (.setCacheIdents rd (boolean (:cache-idents opts)))
     (.readEnvelope rd)
     (.readAny rd))))

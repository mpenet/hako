(ns s-exp.meep.writer
  "Value dispatch for meep encode. Hot-path emission lives in
  com.s_exp.meep.Writer (Java)."
  (:require [s-exp.meep.ext :as ext])
  (:import (clojure.lang BigInt IPersistentMap IPersistentSet IPersistentVector
                         IRecord ISeq Keyword PersistentQueue PersistentTreeMap
                         PersistentTreeSet Ratio Symbol)
           (com.s_exp.meep Format Writer)
           (java.math BigDecimal BigInteger)
           (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(def ^:private LONG-ARRAY-CLASS (class (long-array 0)))
(def ^:private DOUBLE-ARRAY-CLASS (class (double-array 0)))

(defn- long-array? [x] (instance? LONG-ARRAY-CLASS x))
(defn- double-array? [x] (instance? DOUBLE-ARRAY-CLASS x))

(declare write-value!)

(defn- homogeneous-class
  "Return `Long`, `Double`, or nil for the shared boxed class of a
  non-empty vector's elements. Bails out early on the first mismatch."
  [^IPersistentVector v]
  (let [n (.count v)
        first-el (.nth v 0)]
    (when (or (instance? Long first-el) (instance? Double first-el))
      (let [target (class first-el)]
        (loop [i 1]
          (cond
            (>= i n) target
            (identical? target (class (.nth v i))) (recur (unchecked-inc i))
            :else nil))))))

(defn- pack-longs!
  [^Writer w ^IPersistentVector v]
  (let [n (.count v)
        arr (long-array n)]
    (dotimes [i n]
      (aset arr (int i) (long (.nth v i))))
    (.writeLongArray w arr)))

(defn- pack-doubles!
  [^Writer w ^IPersistentVector v]
  (let [n (.count v)
        arr (double-array n)]
    (dotimes [i n]
      (aset arr (int i) (double (.nth v i))))
    (.writeDoubleArray w arr)))

(defn- write-vector!
  [^Writer w ^IPersistentVector v]
  (let [n (.count v)]
    (if (and (pos? n) (.packHomogeneous w))
      (let [homo (homogeneous-class v)]
        (cond
          (identical? homo Long) (pack-longs! w v)
          (identical? homo Double) (pack-doubles! w v)
          :else (do (.writeVectorHeader w n)
                    (dotimes [i n]
                      (write-value! w (.nth v i))))))
      (do (.writeVectorHeader w n)
          (dotimes [i n]
            (write-value! w (.nth v i)))))))

(defn- write-seq!
  [^Writer w s]
  (let [xs (vec s)
        n (count xs)]
    (.writeListHeader w n)
    (dotimes [i n]
      (write-value! w (nth xs i)))))

(defn- write-set!
  [^Writer w ^IPersistentSet s]
  (.writeSetHeader w (.count s))
  (reduce (fn [_ x] (write-value! w x) nil) nil s))

(defn- write-map!
  [^Writer w ^IPersistentMap m]
  (.writeMapHeader w (.count m))
  (reduce-kv
   (fn [_ k v]
     (write-value! w k)
     (write-value! w v)
     nil)
   nil m))

;; -- Bignumeric --------------------------------------------------------------

(defn- write-bigint!
  [^Writer w ^BigInteger x]
  (let [bs (.toByteArray x)]
    (.putByte w (Format/tag Format/M_BIGNUM Format/BIG_BIGINT))
    (.putTierValue w (alength bs))
    (.putBytes w bs)))

(defn- write-clj-bigint!
  [^Writer w ^BigInt x]
  (write-bigint! w (.toBigInteger x)))

(defn- write-bigdec!
  [^Writer w ^BigDecimal x]
  (let [scale (.scale x)
        unscaled (.unscaledValue x)
        bs (.toByteArray unscaled)]
    (.putByte w (Format/tag Format/M_BIGNUM Format/BIG_BIGDEC))
    (.putI32 w scale)
    (.putTierValue w (alength bs))
    (.putBytes w bs)))

(defn- write-ratio!
  [^Writer w ^Ratio x]
  (let [num-bs (.toByteArray (.numerator x))
        den-bs (.toByteArray (.denominator x))]
    (.putByte w (Format/tag Format/M_BIGNUM Format/BIG_RATIO))
    (.putTierValue w (alength num-bs))
    (.putBytes w num-bs)
    (.putTierValue w (alength den-bs))
    (.putBytes w den-bs)))

;; -- Extensions --------------------------------------------------------------

(defn- write-sorted-set!
  [^Writer w ^PersistentTreeSet s]
  (when-not (ext/default-comparator? s)
    (throw (ex-info "meep: cannot encode sorted-set with custom comparator"
                    {:comparator (.comparator s)})))
  (.putByte w (Format/tag Format/M_EXT Format/EXT_SORTED_SET))
  (.putTierValue w (.count s))
  (reduce (fn [_ x] (write-value! w x) nil) nil s))

(defn- write-sorted-map!
  [^Writer w ^PersistentTreeMap m]
  (when-not (ext/default-comparator? m)
    (throw (ex-info "meep: cannot encode sorted-map with custom comparator"
                    {:comparator (.comparator m)})))
  (.putByte w (Format/tag Format/M_EXT Format/EXT_SORTED_MAP))
  (.putTierValue w (.count m))
  (reduce-kv
   (fn [_ k v]
     (write-value! w k)
     (write-value! w v)
     nil)
   nil m))

(defn- write-queue!
  [^Writer w ^PersistentQueue q]
  (let [n (count q)]
    (.putByte w (Format/tag Format/M_EXT Format/EXT_QUEUE))
    (.putTierValue w n)
    (reduce (fn [_ x] (write-value! w x) nil) nil q)))

(defn- write-record!
  [^Writer w ^IRecord x]
  (let [klass (class x)
        info (ext/record-info-by-class klass)]
    (when-not info
      (throw (ex-info "meep: record class not registered"
                      {:class (.getName klass)})))
    (.putByte w (Format/tag Format/M_EXT Format/EXT_RECORD))
    (.writeInterned w Format/M_SYM nil (.getName klass))
    (.putTierValue w (:field-count info))
    (doseq [k (:field-kws info)]
      (write-value! w (get x k)))))

(defn- write-with-meta!
  [^Writer w x meta-map]
  (.putByte w (Format/tag Format/M_EXT Format/EXT_WITH_META))
  ;; Inner value first, then metadata map. Recurse into normal dispatch
  ;; but suppress double-wrapping by temporarily masking the meta.
  (write-value! w (vary-meta x (constantly nil)))
  (write-value! w meta-map))

;; -- Top-level dispatch ------------------------------------------------------

(defn- write-scalar-or-composite!
  [^Writer w x]
  (cond
    (nil? x) (.writeNil w)
    (boolean? x) (if x (.writeTrue w) (.writeFalse w))
    (instance? Long x) (.writeLong w (long x))
    (instance? Keyword x) (.writeInterned w Format/M_KW
                                          (.getNamespace ^Keyword x)
                                          (.getName ^Keyword x))
    (instance? String x) (.writeString w ^String x)
    (instance? IRecord x) (write-record! w x)
    (instance? PersistentQueue x) (write-queue! w x)
    (instance? PersistentTreeSet x) (write-sorted-set! w x)
    (instance? PersistentTreeMap x) (write-sorted-map! w x)
    (instance? IPersistentVector x) (write-vector! w x)
    (instance? IPersistentMap x) (write-map! w x)
    (instance? IPersistentSet x) (write-set! w x)
    (instance? Symbol x) (.writeInterned w Format/M_SYM
                                         (.getNamespace ^Symbol x)
                                         (.getName ^Symbol x))
    (instance? Double x) (.writeDouble w (double x))
    (instance? Float x) (.writeFloat w (float x))
    (instance? Integer x) (.writeLong w (long x))
    (instance? Short x) (.writeLong w (long x))
    (instance? Byte x) (.writeLong w (long x))
    (instance? Character x) (.writeChar w (int (.charValue ^Character x)))
    (instance? UUID x) (.writeUuid w
                                   (.getMostSignificantBits ^UUID x)
                                   (.getLeastSignificantBits ^UUID x))
    (instance? Instant x) (.writeInstant w
                                         (.getEpochSecond ^Instant x)
                                         (.getNano ^Instant x))
    (instance? BigInteger x) (write-bigint! w x)
    (instance? BigInt x) (write-clj-bigint! w x)
    (instance? BigDecimal x) (write-bigdec! w x)
    (instance? Ratio x) (write-ratio! w x)
    (bytes? x) (.writeBytes w ^bytes x)
    (long-array? x) (.writeLongArray w ^longs x)
    (double-array? x) (.writeDoubleArray w ^doubles x)
    (instance? ISeq x) (write-seq! w x)
    (instance? Iterable x) (write-seq! w x)
    :else
    (if-let [info (ext/user-tag-for-class (class x))]
      (let [mark (.beginUserTag w (int (:id info)))]
        ((:write-fn info) w x)
        (.endUserTag w mark))
      (throw (ex-info "meep: no writer for value"
                      {:type (class x) :value x})))))

(defn write-value!
  "Dispatch and encode a single Clojure value. Emits an extension
  `with-meta` wrapper when the writer is in meta-preserving mode and the
  value carries non-empty metadata."
  [^Writer w x]
  (if (and (.writeMeta w)
           (instance? clojure.lang.IObj x))
    (let [m (meta x)]
      (if (and m (seq m))
        (write-with-meta! w x m)
        (write-scalar-or-composite! w x)))
    (write-scalar-or-composite! w x)))

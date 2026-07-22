(ns s-exp.meep.reader
  "Value dispatch for meep decode. Hot-path parsing lives in
  com.s_exp.meep.Reader (Java)."
  (:require [s-exp.meep.ext :as ext])
  (:import (clojure.lang PersistentList PersistentQueue)
           (com.s_exp.meep Format Reader)
           (java.lang.invoke MethodHandle)
           (java.math BigDecimal BigInteger)
           (java.time Instant)
           (java.util Arrays UUID)))

(set! *warn-on-reflection* true)

(defn- probe-array-map-threshold
  "Return the largest map size that stays PersistentArrayMap when grown
  via `assoc`. Uses `key-fn` to generate keys. Adapts to whatever
  threshold the running Clojure version uses (e.g. 8 pre-1.13, 64 for
  keyword-only in 1.13+)."
  [key-fn]
  (loop [n 1 m (array-map)]
    (let [m' (assoc m (key-fn n) n)]
      (cond
        (not (instance? clojure.lang.PersistentArrayMap m')) (dec n)
        (>= n 128) n
        :else (recur (inc n) m')))))

(def ^:private ARRAY-MAP-THRESHOLD
  (long (probe-array-map-threshold #(str "k" %))))

(def ^:private ARRAY-MAP-KW-THRESHOLD
  (long (probe-array-map-threshold #(keyword (str "k" %)))))

(defn- all-keyword-keys?
  [^objects arr ^long n]
  (loop [i 0]
    (if (>= i n)
      true
      (if (keyword? (aget arr (* 2 i)))
        (recur (unchecked-inc i))
        false))))

(declare read-value!)

(defn- read-interned-payload!
  [^Reader r ^long tier-code]
  (let [total-len (.readTierPayload r (int tier-code))
        ns-len (.getByte r)
        name-len (- total-len 1 ns-len)
        ns-str (when (pos? ns-len) (.getString r (int ns-len)))
        name-str (.getString r (int name-len))]
    [ns-str name-str]))

(defn- read-keyword!
  [^Reader r ^long tier-code]
  (let [[ns-str name-str] (read-interned-payload! r tier-code)
        kw (keyword ns-str name-str)]
    (.internAdd r kw)
    kw))

(defn- read-symbol!
  [^Reader r ^long tier-code]
  (let [[ns-str name-str] (read-interned-payload! r tier-code)
        sym (symbol ns-str name-str)]
    (.internAdd r sym)
    sym))

(defn- read-symref!
  [^Reader r ^long tier-code]
  (let [idx (.readTierPayload r (int tier-code))]
    (.internGet r (int idx))))

(defn- read-vector!
  [^Reader r ^long tier-code]
  (let [n (.readTierPayload r (int tier-code))
        t (transient [])]
    (dotimes [_ n]
      (conj! t (read-value! r)))
    (persistent! t)))

(defn- read-list!
  [^Reader r ^long tier-code]
  (let [n (.readTierPayload r (int tier-code))
        arr (object-array n)]
    (dotimes [i n]
      (aset arr (int i) (read-value! r)))
    (PersistentList/create (Arrays/asList arr))))

(defn- read-set!
  [^Reader r ^long tier-code]
  (let [n (.readTierPayload r (int tier-code))
        t (transient #{})]
    (dotimes [_ n]
      (conj! t (read-value! r)))
    (persistent! t)))

(defn- read-map!
  [^Reader r ^long tier-code]
  (let [n (.readTierPayload r (int tier-code))]
    (if (zero? n)
      {}
      (let [arr (object-array (* 2 n))]
        (dotimes [i n]
          (aset arr (* 2 i) (read-value! r))
          (aset arr (unchecked-inc (* 2 i)) (read-value! r)))
        (cond
          (<= n ARRAY-MAP-THRESHOLD)
          (clojure.lang.PersistentArrayMap. arr)

          (and (<= n ARRAY-MAP-KW-THRESHOLD)
               (all-keyword-keys? arr n))
          (clojure.lang.PersistentArrayMap. arr)

          :else
          (clojure.lang.PersistentHashMap/create arr))))))

(defn- read-special!
  [^Reader r ^long low]
  (case (int low)
    0 nil
    1 true
    2 false
    3 Double/NaN
    4 Double/POSITIVE_INFINITY
    5 Double/NEGATIVE_INFINITY
    6 (let [hi (.getI64 r) lo (.getI64 r)] (UUID. hi lo))
    7 (let [s (.getI64 r) ns (.getU32 r)] (Instant/ofEpochSecond s ns))
    8 (char (.getU16 r))
    (throw (ex-info "meep: unknown special" {:low low}))))

;; -- Bignumeric --------------------------------------------------------------

(defn- read-bigint-bytes! ^BigInteger [^Reader r]
  (let [n (.readTierValue r)
        bs (.getBytes r (int n))]
    (BigInteger. bs)))

(defn- read-bignumeric!
  [^Reader r ^long low]
  (case (int low)
    0 (bigint (read-bigint-bytes! r))
    1 (let [scale (.getI32 r)
            unscaled (read-bigint-bytes! r)]
        (BigDecimal. unscaled (int scale)))
    2 (let [num (read-bigint-bytes! r)
            den (read-bigint-bytes! r)]
        (/ (bigint num) (bigint den)))
    (throw (ex-info "meep: unknown bignumeric subtype" {:low low}))))

;; -- Extensions --------------------------------------------------------------

(defn- read-sorted-set! [^Reader r]
  (let [n (.readTierValue r)]
    (loop [i 0 acc (sorted-set)]
      (if (< i n)
        (recur (unchecked-inc i) (conj acc (read-value! r)))
        acc))))

(defn- read-sorted-map! [^Reader r]
  (let [n (.readTierValue r)]
    (loop [i 0 acc (sorted-map)]
      (if (< i n)
        (let [k (read-value! r)
              v (read-value! r)]
          (recur (unchecked-inc i) (assoc acc k v)))
        acc))))

(defn- read-queue! [^Reader r]
  (let [n (.readTierValue r)]
    (loop [i 0 acc PersistentQueue/EMPTY]
      (if (< i n)
        (recur (unchecked-inc i) (conj acc (read-value! r)))
        acc))))

(defn- read-record! [^Reader r]
  (let [classname-sym (read-value! r)
        classname (str classname-sym)
        info (ext/record-info-by-name classname)
        _ (when-not info
            (throw (ex-info "meep: unknown record class"
                            {:class classname})))
        n (.readTierValue r)
        args (object-array n)]
    (dotimes [i n]
      (aset args (int i) (read-value! r)))
    (.invokeWithArguments ^MethodHandle (:ctor-mh info) args)))

(defn- read-with-meta! [^Reader r]
  (let [v (read-value! r)
        m (read-value! r)]
    (if (instance? clojure.lang.IObj v)
      (with-meta v m)
      v)))

(defn- read-user-tag! [^Reader r]
  (let [id (bit-and (.getU32 r) 0xFFFFFFFF)
        len (.readTierValue r)
        reader-info (ext/user-tag-reader id)]
    (cond
      reader-info
      (let [start (.pos r)
            v ((:read-fn reader-info) r)
            end (.pos r)
            consumed (- end start)]
        (when (not= consumed len)
          (throw (ex-info "meep: user-tag read-fn consumed wrong byte count"
                          {:id id :expected len :actual consumed})))
        v)

      (.isTolerant r)
      (let [slice (.sliceBytes r len)]
        (ext/->TaggedValue id slice))

      :else
      (throw (ex-info "meep: unknown user-tag"
                      {:type ::unknown-user-tag :id id})))))

(defn- read-extension!
  [^Reader r ^long low]
  (case (int low)
    0 (read-sorted-set! r)
    1 (read-sorted-map! r)
    2 (read-queue! r)
    3 (read-record! r)
    4 (read-with-meta! r)
    5 (let [n (.readTierValue r)] (.readLongArray r (int n)))
    6 (let [n (.readTierValue r)] (.readDoubleArray r (int n)))
    15 (read-user-tag! r)
    (throw (ex-info "meep: unknown extension subtype" {:low low}))))

;; -- Dispatch ----------------------------------------------------------------

(defn read-value!
  [^Reader r]
  (let [tag-byte (.getByte r)
        major (Format/majorOf tag-byte)
        low (Format/lowOf tag-byte)]
    (case (int major)
      0x00 (.readTierPayload r (int low))
      0x10 (Format/zigZagDecode (.readTierPayload r (int low)))
      0x20 (case (int low)
             0 (.getF32 r)
             1 (.getF64 r)
             (throw (ex-info "meep: unknown float subtype" {:low low})))
      0x30 (let [n (.readTierPayload r (int low))]
             (if (.isZeroCopy r)
               (.sliceBytes r n)
               (.getBytes r (int n))))
      0x40 (let [n (.readTierPayload r (int low))] (.getString r (int n)))
      0x50 (read-keyword! r low)
      0x60 (read-symbol! r low)
      0x70 (read-vector! r low)
      0x80 (read-list! r low)
      0x90 (read-set! r low)
      0xA0 (read-map! r low)
      0xC0 (read-symref! r low)
      0xD0 (read-bignumeric! r low)
      0xE0 (read-extension! r low)
      0xF0 (read-special! r low)
      (throw (ex-info "meep: unknown major type"
                      {:tag tag-byte :major major :low low})))))

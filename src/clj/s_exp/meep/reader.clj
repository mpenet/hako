(ns s-exp.meep.reader
  "Value dispatch for meep decode. Hot-path parsing lives in
  com.s_exp.meep.Reader (Java)."
  (:import (clojure.lang PersistentList)
           (com.s_exp.meep Format Reader)
           (java.time Instant)
           (java.util Arrays UUID)))

(set! *warn-on-reflection* true)

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
  (let [n (.readTierPayload r (int tier-code))
        t (transient {})]
    (dotimes [_ n]
      (let [k (read-value! r)
            v (read-value! r)]
        (assoc! t k v)))
    (persistent! t)))

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
      0x30 (let [n (.readTierPayload r (int low))] (.getBytes r (int n)))
      0x40 (let [n (.readTierPayload r (int low))] (.getString r (int n)))
      0x50 (read-keyword! r low)
      0x60 (read-symbol! r low)
      0x70 (read-vector! r low)
      0x80 (read-list! r low)
      0x90 (read-set! r low)
      0xA0 (read-map! r low)
      0xC0 (read-symref! r low)
      0xF0 (read-special! r low)
      (throw (ex-info "meep: unknown major type"
                      {:tag tag-byte :major major :low low})))))

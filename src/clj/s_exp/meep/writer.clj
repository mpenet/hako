(ns s-exp.meep.writer
  "Value dispatch for meep encode. Hot-path emission lives in
  com.s_exp.meep.Writer (Java)."
  (:import (clojure.lang IPersistentMap IPersistentSet IPersistentVector
                         ISeq Keyword Symbol)
           (com.s_exp.meep Format Writer)
           (java.time Instant)
           (java.util UUID)))

(set! *warn-on-reflection* true)

(declare write-value!)

(defn- write-vector!
  [^Writer w ^IPersistentVector v]
  (let [n (.count v)]
    (.writeVectorHeader w n)
    (dotimes [i n]
      (write-value! w (.nth v i)))))

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

(defn write-value!
  "Dispatch and encode a single Clojure value."
  [^Writer w x]
  (cond
    (nil? x) (.writeNil w)
    (boolean? x) (if x (.writeTrue w) (.writeFalse w))
    (instance? Long x) (.writeLong w (long x))
    (instance? Keyword x) (.writeInterned w Format/M_KW
                                          (.getNamespace ^Keyword x)
                                          (.getName ^Keyword x))
    (instance? String x) (.writeString w ^String x)
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
    (bytes? x) (.writeBytes w ^bytes x)
    (instance? ISeq x) (write-seq! w x)
    (instance? Iterable x) (write-seq! w x)
    :else (throw (ex-info "meep: no writer for value"
                          {:type (class x) :value x}))))

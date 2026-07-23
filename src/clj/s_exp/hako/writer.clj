(ns s-exp.hako.writer
  "Thin dispatch bridge for hako encode. The hot path — instanceof
  dispatch, container recursion, all scalar encodings — lives in
  com.s_exp.hako.Writer.writeAny. This namespace only handles the
  callback (records, sorted colls, queue, user-tags)."
  (:require [s-exp.hako.ext :as ext])
  (:import (clojure.lang IRecord PersistentQueue PersistentTreeMap
                         PersistentTreeSet)
           (com.s_exp.hako Format Writer Writer$UnknownHandler)))

(set! *warn-on-reflection* true)

(defn- write-record!
  [^Writer w x]
  (let [klass (class x)
        info (ext/record-info-by-class klass)]
    (when-not info
      (throw (ex-info "hako: record class not registered"
                      {:class (.getName klass)})))
    (.putByte w (Format/tag Format/M_EXT Format/EXT_RECORD))
    (.writeInterned w Format/M_SYM (.getName klass) nil (.getName klass))
    (.putTierValue w (:field-count info))
    (if (:java-record? info)
      (let [^"[Ljava.lang.Object;" xs (into-array Object [x])]
        (doseq [^java.lang.invoke.MethodHandle mh (:accessor-mhs info)]
          (.writeAny w (.invokeWithArguments mh xs))))
      (doseq [k (:field-kws info)]
        (.writeAny w (get x k))))))

(def ^:private warned-custom-cmp?
  "Emits the coercion warning at most once per JVM."
  (atom false))

(defn- maybe-warn-custom-cmp! [coll]
  (when (compare-and-set! warned-custom-cmp? false true)
    (binding [*out* *err*]
      (println (str "hako: WARNING — coercing custom comparator on "
                    (class coll)
                    " to natural ordering on encode; the comparator will "
                    "not be restored on decode.")))))

(defn- write-sorted-set!
  [^Writer w ^PersistentTreeSet s]
  (when-not (ext/default-comparator? s)
    (if (.coerceCustomComparator w)
      (maybe-warn-custom-cmp! s)
      (throw (ex-info "hako: cannot encode sorted-set with custom comparator"
                      {:comparator (.comparator s)}))))
  (.putByte w (Format/tag Format/M_EXT Format/EXT_SORTED_SET))
  (.putTierValue w (.count s))
  (reduce (fn [_ x] (.writeAny w x) nil) nil s))

(defn- write-sorted-map!
  [^Writer w ^PersistentTreeMap m]
  (when-not (ext/default-comparator? m)
    (if (.coerceCustomComparator w)
      (maybe-warn-custom-cmp! m)
      (throw (ex-info "hako: cannot encode sorted-map with custom comparator"
                      {:comparator (.comparator m)}))))
  (.putByte w (Format/tag Format/M_EXT Format/EXT_SORTED_MAP))
  (.putTierValue w (.count m))
  (reduce-kv
   (fn [_ k v]
     (.writeAny w k)
     (.writeAny w v)
     nil)
   nil m))

(defn- write-queue!
  [^Writer w ^PersistentQueue q]
  (.putByte w (Format/tag Format/M_EXT Format/EXT_QUEUE))
  (.putTierValue w (count q))
  (reduce (fn [_ x] (.writeAny w x) nil) nil q))

(defn- write-user-tag!
  [^Writer w x info]
  (let [mark (.beginUserTag w (int (:id info)))]
    ((:write-fn info) w x)
    (.endUserTag w mark)))

(def ^Writer$UnknownHandler HANDLER
  (reify Writer$UnknownHandler
    (write [_ w v]
      (let [w ^Writer w
            klass (class v)]
        (cond
          (or (instance? IRecord v) (.isRecord klass))
          (if (ext/record-info-by-class klass)
            (write-record! w v)
            (throw (ex-info "hako: record class not registered"
                            {:class (.getName klass)})))

          (instance? PersistentTreeSet v) (write-sorted-set! w v)
          (instance? PersistentTreeMap v) (write-sorted-map! w v)
          (instance? PersistentQueue v) (write-queue! w v)

          :else
          (if-let [info (ext/user-tag-for-class klass)]
            (write-user-tag! w v info)
            (throw (ex-info "hako: no writer for value"
                            {:type klass :value v}))))))))

(defn install-handler!
  "Attach the Clojure fallback handler to `w`. Must be called after
  reset or on a fresh writer, before any writeAny call."
  [^Writer w]
  (.setUnknownHandler w HANDLER))

(defn write-value!
  "Backwards-compatible facade around `.writeAny`. New code should call
  `.writeAny` directly after `install-handler!`."
  [^Writer w x]
  (.writeAny w x))

(ns s-exp.hako.writer
  "Thin dispatch bridge for hako encode. Scalar / collection / record /
  sorted-coll / queue encoding all live in `com.s_exp.hako.Writer`
  (Java). This namespace only handles the fallback callback for
  user-tagged types + the custom-comparator warning."
  (:require [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako Writer
                           Writer$CustomComparatorWarner
                           Writer$UnknownHandler)))

(set! *warn-on-reflection* true)

(def ^:private warned-custom-cmp?
  "Emits the coercion warning at most once per JVM."
  (atom false))

(def ^Writer$CustomComparatorWarner custom-cmp-warner
  (reify Writer$CustomComparatorWarner
    (warn [_ coll]
      (when (compare-and-set! warned-custom-cmp? false true)
        (binding [*out* *err*]
          (println (str "hako: WARNING — coercing custom comparator on "
                        (class coll)
                        " to natural ordering on encode; the comparator will "
                        "not be restored on decode.")))))))

(defn- write-user-tag!
  [^Writer w x info]
  (let [mark (.beginUserTag w (int (:id info)))]
    ((:write-fn info) w x)
    (.endUserTag w mark)))

(def ^Writer$UnknownHandler handler
  (reify Writer$UnknownHandler
    (write [_ w v]
      (let [w ^Writer w
            klass (class v)]
        (if-let [info (ext/user-tag-for-class klass)]
          (write-user-tag! w v info)
          (throw (ex-info "hako: no writer for value"
                          {:type klass :value v})))))))

(defn install-handler!
  "Attach the Clojure fallback handler + custom-comparator warner to
  `w`. Called once at Writer creation — both retained across `.reset`.
  The Writer's Java-side check consults `com.s_exp.hako.UserTagRegistry`
  directly for the user-tag-vs-generic-Iterable dispatch decision."
  [^Writer w]
  (.setUnknownHandler w handler)
  (.setCustomComparatorWarner w custom-cmp-warner))

(defn write-value!
  "Backwards-compatible facade around `.writeAny`."
  [^Writer w x]
  (.writeAny w x))

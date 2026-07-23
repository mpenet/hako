(ns s-exp.hako.reader
  "Thin dispatch bridge for hako decode. Scalar / collection / record
  decoding all live in com.s_exp.hako.Reader.readAny (Java). This
  namespace threshold-probes the runtime's PersistentArrayMap behavior
  and installs a Clojure user-tag callback."
  (:require [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako Reader Reader$ExtensionHandler)))

(set! *warn-on-reflection* true)

(defn- probe-array-map-threshold
  "Return the largest map size that stays PersistentArrayMap when grown
  via `assoc`. Adapts to the running Clojure version (e.g. 8 pre-1.13,
  64 for keyword-only in 1.13+)."
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

;; -- User-tag handler --------------------------------------------------------

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
          (throw (ex-info "hako: user-tag read-fn consumed wrong byte count"
                          {:id id :expected len :actual consumed})))
        v)

      (.isTolerant r)
      (let [slice (.sliceBytes r len)]
        (ext/->TaggedValue id slice))

      :else
      (throw (ex-info "hako: unknown user-tag"
                      {:type ::unknown-user-tag :id id})))))

(def ^Reader$ExtensionHandler HANDLER
  (reify Reader$ExtensionHandler
    (readUserTag [_ r] (read-user-tag! ^Reader r))))

(defn configure!
  "Prepare a fresh (or reset) Reader for decode: set the array-map
  thresholds probed from the running Clojure runtime and install the
  user-tag callback. Called once at Reader creation."
  [^Reader r]
  (.setArrayMapThresholds r (int ARRAY-MAP-THRESHOLD) (int ARRAY-MAP-KW-THRESHOLD))
  (.setExtensionHandler r HANDLER))

(defn read-value!
  "Backwards-compatible facade around `.readAny`."
  [^Reader r]
  (.readAny r))

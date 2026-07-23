(ns s-exp.hako.ext
  "Extension registry: records, sorted-collection helpers, user tags."
  (:import (clojure.lang PersistentTreeMap PersistentTreeSet)
           (java.lang.foreign MemorySegment)
           (java.lang.invoke MethodHandle MethodHandles MethodType)
           (java.lang.reflect Constructor)))

(set! *warn-on-reflection* true)

(defrecord TaggedValue [^long ext ^MemorySegment bytes])

(defn tagged-value? [x]
  (instance? TaggedValue x))

;; -- Default comparator detection -------------------------------------------

(def ^:private ^java.util.Comparator DEFAULT-CMP
  (.comparator ^PersistentTreeSet (sorted-set)))

(defn default-comparator?
  "True when `sorted-coll` uses the natural-ordering comparator that
  `sorted-set` / `sorted-map` install by default."
  [sorted-coll]
  (identical? DEFAULT-CMP
              (cond
                (instance? PersistentTreeSet sorted-coll)
                (.comparator ^PersistentTreeSet sorted-coll)

                (instance? PersistentTreeMap sorted-coll)
                (.comparator ^PersistentTreeMap sorted-coll))))

;; -- Record registry --------------------------------------------------------

(def ^:private record-registry (atom {}))

(defn- clj-record?
  "True when `klass` is a Clojure defrecord — it exposes a static
  `getBasis` method returning the field-name vector."
  [^Class klass]
  (try
    (.getDeclaredMethod klass "getBasis" (into-array Class []))
    true
    (catch NoSuchMethodException _ false)))

(defn- clj-record-basis
  "Ordered vector of basis field-symbols for a Clojure defrecord."
  [^Class klass]
  (let [m (.getDeclaredMethod klass "getBasis" (into-array Class []))]
    (.invoke m nil (into-array Object []))))

(defn- java-record-components
  "Ordered vector of field-name strings for a Java record (JEP 395)."
  [^Class klass]
  (mapv #(.getName ^java.lang.reflect.RecordComponent %)
        (.getRecordComponents klass)))

(defn- find-canonical-ctor
  ^Constructor [^Class klass ^long n]
  (or (some (fn [^Constructor c]
              (when (= (.getParameterCount c) n) c))
            (.getConstructors klass))
      (throw (ex-info "hako: canonical ctor not found for record"
                      {:class (.getName klass) :field-count n}))))

(defn- java-record-accessor-mhs
  "Cache MethodHandles for each RecordComponent's accessor method."
  [^Class klass]
  (let [lookup (MethodHandles/lookup)]
    (mapv (fn [^java.lang.reflect.RecordComponent rc]
            (.unreflect lookup (.getAccessor rc)))
          (.getRecordComponents klass))))

(defn- record-info
  "Compute registry entry for a record class (Clojure defrecord or Java
  record)."
  [^Class klass]
  (let [java-rec? (.isRecord klass)
        field-names (cond
                      java-rec? (java-record-components klass)
                      (clj-record? klass) (mapv name (clj-record-basis klass))
                      :else (throw (ex-info "hako: not a record class"
                                            {:class (.getName klass)})))
        n (count field-names)
        field-kws (mapv keyword field-names)
        ctor (find-canonical-ctor klass n)
        raw-mh (.unreflectConstructor (MethodHandles/lookup) ctor)
        ;; Adapt the ctor MH so all params accept Object (via
        ;; explicitCastArguments' permissive Number→primitive coercion).
        ;; Otherwise Long → int / short / byte narrowing fails at invoke.
        generic (MethodType/methodType (.returnType (.type raw-mh))
                                       (into-array Class (repeat n Object)))
        mh (MethodHandles/explicitCastArguments raw-mh generic)
        accessor-mhs (when java-rec? (java-record-accessor-mhs klass))]
    {:class klass
     :java-record? java-rec?
     :field-count n
     :field-kws field-kws
     :field-names field-names
     :accessor-mhs accessor-mhs
     :ctor-mh mh}))

(defn register-record!
  "Register a Clojure defrecord OR Java record class so hako can encode
  and decode its instances.

  Reflects on the class once to discover field order and cache a
  MethodHandle for the canonical positional constructor."
  [^Class klass]
  (let [info (record-info klass)]
    (swap! record-registry assoc (.getName klass) info)
    klass))

(defn record-info-by-class [^Class klass]
  (get @record-registry (.getName klass)))

(defn record-info-by-name [^String classname]
  (get @record-registry classname))

;; -- User-tag registry ------------------------------------------------------
;;
;; Keyed two ways:
;;   :by-class {Class -> {:id long :write-fn fn}}    — for encode dispatch
;;   :by-id    {long  -> {:read-fn fn}}              — for decode dispatch
;;
;; write-fn signature: (fn [Writer value])  — write payload bytes only.
;;                                            Framework wraps with the
;;                                            0xEF header + u32 id + u32 length.
;; read-fn signature:  (fn [Reader])        — parse one value from the
;;                                            length-bounded payload.

(def ^:private user-tag-registry
  (atom {:by-class {} :by-id {}}))

(defn register-user-tag!
  "Register a user extension tag `id` for `klass`. See ns docstring for
  callback signatures."
  [^long id ^Class klass write-fn read-fn]
  (when (or (< id 0) (> id 0xFFFFFFFF))
    (throw (ex-info "user-tag id out of range" {:id id})))
  (swap! user-tag-registry
         (fn [reg]
           (-> reg
               (assoc-in [:by-class klass] {:id id :write-fn write-fn})
               (assoc-in [:by-id id] {:read-fn read-fn}))))
  id)

(defn user-tag-for-class [^Class klass]
  (get-in @user-tag-registry [:by-class klass]))

(defn user-tag-reader [id]
  (get-in @user-tag-registry [:by-id id]))

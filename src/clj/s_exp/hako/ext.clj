(ns s-exp.hako.ext
  "Extension registry: records, sorted-collection helpers, user tags."
  (:import (clojure.lang Keyword PersistentTreeMap PersistentTreeSet)
           (com.s_exp.hako RecordInfo RecordRegistry)
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
;;
;; The Java-side com.s_exp.hako.RecordRegistry stores the actual entries;
;; this ns builds a RecordInfo from a Class then hands it over. Encode /
;; decode dispatch is fully in Java — no Clojure atom is consulted on
;; the hot path.

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
  "MethodHandle per Java record accessor. Adapted so each returns
  Object instead of the declared primitive type — the invoke path
  auto-boxes without an extra reflection hop."
  [^Class klass]
  (let [lookup (MethodHandles/lookup)]
    (mapv (fn [^java.lang.reflect.RecordComponent rc]
            (let [raw (.unreflect lookup (.getAccessor rc))
                  generic (MethodType/methodType Object
                                                 ^"[Ljava.lang.Class;"
                                                 (into-array Class [klass]))]
              (MethodHandles/explicitCastArguments raw generic)))
          (.getRecordComponents klass))))

(defn register-record!
  "Register a Clojure defrecord OR Java record class so hako can encode
  and decode its instances.

  Reflects on the class once, caches a MethodHandle for the canonical
  constructor (and per-field accessor MHs for Java records), and hands
  the resulting RecordInfo to the Java-side registry."
  [^Class klass]
  (let [java-rec? (.isRecord klass)
        field-names (cond
                      java-rec? (java-record-components klass)
                      (clj-record? klass) (mapv name (clj-record-basis klass))
                      :else (throw (ex-info "hako: not a record class"
                                            {:class (.getName klass)})))
        n (count field-names)
        ctor (find-canonical-ctor klass n)
        raw-mh (.unreflectConstructor (MethodHandles/lookup) ctor)
        ctor-generic (MethodType/methodType ^Class (.returnType (.type ^MethodHandle raw-mh))
                                            ^"[Ljava.lang.Class;"
                                            (into-array Class (repeat n Object)))
        ctor-mh (MethodHandles/explicitCastArguments raw-mh ctor-generic)
        field-kws (when-not java-rec?
                    (into-array Keyword (map keyword field-names)))
        accessor-mhs (when java-rec?
                       (into-array MethodHandle (java-record-accessor-mhs klass)))
        info (RecordInfo. klass (.getName klass) (int n) (boolean java-rec?)
                          field-kws accessor-mhs ctor-mh)]
    (RecordRegistry/put info)
    klass))

(defn record-info-by-class [^Class klass]
  (RecordRegistry/byClass klass))

(defn record-info-by-name [^String classname]
  (RecordRegistry/byName classname))

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

(ns s-exp.meep.ext
  "Extension registry: records, sorted-collection helpers, user tags."
  (:import (clojure.lang PersistentTreeMap PersistentTreeSet)
           (java.lang.foreign MemorySegment)
           (java.lang.invoke MethodHandle MethodHandles)
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

(defn- record-basis
  "Return the ordered vector of basis field-symbols for a defrecord class."
  [^Class klass]
  (let [m (.getDeclaredMethod klass "getBasis" (into-array Class []))]
    (.invoke m nil (into-array Object []))))

(defn- find-canonical-ctor
  ^Constructor [^Class klass ^long n]
  (or (some (fn [^Constructor c]
              (when (= (.getParameterCount c) n) c))
            (.getConstructors klass))
      (throw (ex-info "meep: canonical ctor not found for record"
                      {:class (.getName klass) :field-count n}))))

(defn register-record!
  "Register a defrecord class so meep can encode / decode its instances.

  Reflects on the class once to discover field order and cache a
  MethodHandle for the canonical positional constructor."
  [^Class klass]
  (let [basis (record-basis klass)
        n (count basis)
        field-kws (mapv (comp keyword name) basis)
        ctor (find-canonical-ctor klass n)
        mh (-> (MethodHandles/lookup)
               (.unreflectConstructor ctor))]
    (swap! record-registry assoc (.getName klass)
           {:class klass
            :field-count n
            :field-kws field-kws
            :ctor-mh mh})
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

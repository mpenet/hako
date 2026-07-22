(ns s-exp.meep.ext
  "Extension registry stubs. Populated in M1."
  (:import (java.lang.foreign MemorySegment)))

(set! *warn-on-reflection* true)

(defrecord TaggedValue [^long ext ^MemorySegment bytes])

(defn tagged-value? [x]
  (instance? TaggedValue x))

(def ^:private user-write-fns (atom {}))
(def ^:private user-read-fns (atom {}))

(defn register-user-tag!
  "Register a user extension tag id with write and read functions.
  `write-fn` : (fn [Writer value]) — writes payload after tag/id bytes.
  `read-fn`  : (fn [Reader])       — parses value."
  [^long id write-fn read-fn]
  (when (or (< id 0) (> id 0xFFFFFFFF))
    (throw (ex-info "user-tag id out of range" {:id id})))
  (swap! user-write-fns assoc id write-fn)
  (swap! user-read-fns assoc id read-fn)
  id)

(defn user-write-fn [^long id]
  (get @user-write-fns id))

(defn user-read-fn [^long id]
  (get @user-read-fns id))

(ns s-exp.hako.fuzz-test
  "Property-based roundtrip: generate arbitrary Clojure data, encode,
  decode, assert (=) preservation."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [s-exp.hako :as hako]))

;; -- Generators --------------------------------------------------------------
;;
;; Avoid NaN (breaks `=`) and things hako doesn't roundtrip identically
;; (metadata by default). Concrete map/set impls may differ; use `=`.

(def gen-non-nan-double
  (gen/such-that #(not (Double/isNaN %)) gen/double 100))

(def gen-scalar
  (gen/one-of
   [(gen/return nil)
    gen/boolean
    gen/small-integer
    gen/large-integer
    gen-non-nan-double
    gen/string-ascii
    gen/keyword
    gen/keyword-ns
    gen/symbol
    gen/symbol-ns
    gen/uuid]))

(def gen-value
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of
      [(gen/vector inner 0 6)
       (gen/list inner)
       (gen/set inner)
       (gen/map inner inner)]))
   gen-scalar))

(defn- roundtrip= [v]
  (= v (hako/decode (hako/encode v))))

(deftest fuzz-roundtrip
  (let [result (tc/quick-check 500 (prop/for-all [v gen-value] (roundtrip= v)))]
    (is (:pass? result)
        (str "fuzz roundtrip failed after " (:num-tests result) " tests: "
             (:shrunk result)))))

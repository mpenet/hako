(ns s-exp.hako.records-clj-test
  "Clojure defrecord encode / decode."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext]))

(defrecord Point [x y])
(defrecord Line [start end label])
(defrecord UnregisteredForSure [x])

(ext/register-record! Point)
(ext/register-record! Line)

(defn- rt [v] (hako/decode (hako/encode v)))

(deftest records
  (testing "simple record"
    (let [p (->Point 1 2)
          r (rt p)]
      (is (= p r))
      (is (instance? Point r))))
  (testing "nested record"
    (let [line (->Line (->Point 0 0) (->Point 3 4) "hypo")
          r (rt line)]
      (is (= line r))
      (is (instance? Line r))
      (is (instance? Point (:start r)))))
  (testing "vector of records — classname interned"
    (let [xs (mapv ->Point (range 100) (range 100 200))
          enc (hako/encode xs)
          r (hako/decode enc)]
      (is (= xs r))
      ;; Classname stored once + 99 symrefs; each Point is 3 fields
      ;; (record tag + symref + count-tier + 2 vals). Ballpark check.
      (is (< (alength enc) 1500))))
  (testing "unregistered class fails"
    (is (thrown? Exception (hako/encode (->UnregisteredForSure 1))))))

(deftest mixed-records-and-scalars
  (testing "record embedded alongside bignumeric + sorted + strings"
    (let [v {:points [(->Point 1 2) (->Point 3 4)]
             :ratio 22/7
             :big 1234567890123456789012345N
             :sorted (sorted-set 5 3 1 2 4)}
          r (rt v)]
      (is (= v r))
      (is (instance? clojure.lang.PersistentTreeSet (:sorted r)))
      (is (instance? Point (first (:points r)))))))

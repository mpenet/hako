(ns s-exp.hako.numerics-test
  "BigInteger, BigDecimal, Ratio, and pre-1970 Instant roundtrips."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.time Instant)))

(defn- rt [v] (hako/decode (hako/encode v)))

(deftest bignumeric
  (testing "BigInteger"
    (is (= 12345N (rt 12345N)))
    (is (= -98765432109876543210987654321N
           (rt -98765432109876543210987654321N))))
  (testing "BigDecimal"
    (is (= 3.14159265358979323846M
           (rt 3.14159265358979323846M)))
    (is (= 0M (rt 0M))))
  (testing "Ratio"
    (is (= 1/3 (rt 1/3)))
    (is (= -22/7 (rt -22/7)))))

(deftest instant-negative
  (testing "pre-1970 Instant with nanos"
    (let [t (Instant/ofEpochSecond -1000000 500000)]
      (is (= t (rt t)))))
  (testing "Year 0001"
    (let [t (Instant/ofEpochSecond -62135596800 0)]
      (is (= t (rt t))))))

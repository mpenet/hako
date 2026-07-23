(ns s-exp.hako.records-java-test
  "Java-record (JEP 395) encode / decode via ext/register-record!."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako.testing Line Point)))

(ext/register-record! Point)
(ext/register-record! Line)

(deftest java-record-roundtrip
  (testing "primitive-field Java record"
    (let [p (Point. 3 4)
          r (hako/decode (hako/encode p))]
      (is (instance? Point r))
      (is (= 3 (.x ^Point r)))
      (is (= 4 (.y ^Point r)))
      (is (= p r))))
  (testing "Java record with nested Java record + String"
    (let [line (Line. (Point. 0 0) (Point. 3 4) "hyp")
          r (hako/decode (hako/encode line))]
      (is (instance? Line r))
      (is (= line r))
      (is (instance? Point (.start ^Line r)))
      (is (= "hyp" (.label ^Line r)))))
  (testing "vector of Java records"
    (let [xs (mapv #(Point. % (inc %)) (range 20))
          r (hako/decode (hako/encode xs))]
      (is (= xs r))
      (is (every? #(instance? Point %) r)))))

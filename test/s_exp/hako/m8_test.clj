(ns s-exp.hako.m8-test
  "prim-ints / prim-floats packed extensions."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(deftest prim-int-array
  (let [xs (int-array [1 2 3 -1 Integer/MAX_VALUE Integer/MIN_VALUE 0])
        r (hako/decode (hako/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^ints r))))

(deftest prim-float-array
  (let [xs (float-array [1.0 -2.5 3.14 Float/MAX_VALUE Float/MIN_VALUE])
        r (hako/decode (hako/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^floats r))))

(deftest prim-arrays-in-container
  (testing "int[] inside a map"
    (let [v {:xs (int-array [1 2 3])}
          r (hako/decode (hako/encode v))]
      (is (java.util.Arrays/equals (:xs v) ^ints (:xs r)))))
  (testing "float[] inside a vector"
    (let [v [(float-array [1.0 2.0]) (float-array [3.0 4.0])]
          r (hako/decode (hako/encode v))]
      (is (java.util.Arrays/equals ^floats (first v) ^floats (first r)))
      (is (java.util.Arrays/equals ^floats (second v) ^floats (second r))))))

(deftest prim-int-size
  (testing "prim-ints frame is smaller than boxed-vector for large ints"
    (let [v (mapv int (repeat 100 Integer/MAX_VALUE))
          plain (hako/encode v)
          packed (hako/encode (int-array v))]
      (is (< (alength packed) (alength plain))))))

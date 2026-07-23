(ns s-exp.hako.prim-arrays-test
  "Packed prim-longs / prim-doubles / prim-ints / prim-floats
  extensions, plus :pack-homogeneous auto-detection."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(deftest prim-long-array
  (let [xs (long-array [1 2 3 -1 Long/MAX_VALUE Long/MIN_VALUE 0])
        r (hako/decode (hako/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^longs r))))

(deftest prim-double-array
  (let [xs (double-array [1.0 -2.5 3.14 Double/MAX_VALUE Double/MIN_VALUE])
        r (hako/decode (hako/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^doubles r))))

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

(deftest pack-homogeneous
  (testing "vector of Long packs to prim-longs (round-trips as long[])"
    (let [v (vec (range 100))
          packed (hako/encode v {:pack-homogeneous true})
          r (hako/decode packed)]
      (is (= (class (long-array 0)) (class r)))
      (is (java.util.Arrays/equals (long-array v) ^longs r))))
  (testing "pack wins on size for large ints"
    (let [v (vec (repeat 100 Long/MAX_VALUE))
          plain (hako/encode v)
          packed (hako/encode v {:pack-homogeneous true})]
      (is (< (alength packed) (alength plain)))))
  (testing "vector of Double packs"
    (let [v (mapv double (range 100))
          packed (hako/encode v {:pack-homogeneous true})
          r (hako/decode packed)]
      (is (= (class (double-array 0)) (class r)))
      (is (java.util.Arrays/equals (double-array v) ^doubles r))))
  (testing "mixed vector falls back to normal encoding"
    (let [v [1 2 "three" 4]
          packed (hako/encode v {:pack-homogeneous true})
          r (hako/decode packed)]
      (is (= v r))))
  (testing "opt off keeps mixed & homogeneous alike"
    (let [v [1 2 3]
          plain (hako/encode v)
          r (hako/decode plain)]
      (is (= v r))
      (is (instance? clojure.lang.IPersistentVector r)))))

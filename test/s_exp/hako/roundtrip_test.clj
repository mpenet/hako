(ns s-exp.hako.roundtrip-test
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.time Instant)
           (java.util UUID)))

(defn- rt [v] (hako/decode (hako/encode v)))

(deftest scalars
  (testing "nil / bool"
    (is (nil?  (rt nil)))
    (is (true?  (rt true)))
    (is (false? (rt false))))
  (testing "small uint (inline tier)"
    (is (= 0 (rt 0)))
    (is (= 11 (rt 11))))
  (testing "uint tiers"
    (is (= 12 (rt 12)))
    (is (= 255 (rt 255)))
    (is (= 65535 (rt 65535)))
    (is (= 4294967295 (rt 4294967295)))
    (is (= Long/MAX_VALUE (rt Long/MAX_VALUE))))
  (testing "sint"
    (is (= -1 (rt -1)))
    (is (= -255 (rt -255)))
    (is (= Long/MIN_VALUE (rt Long/MIN_VALUE))))
  (testing "float / double"
    (is (= 3.14 (rt 3.14)))
    (is (= -0.5 (rt -0.5)))
    (is (Double/isNaN (rt Double/NaN)))
    (is (= Double/POSITIVE_INFINITY (rt Double/POSITIVE_INFINITY)))
    (is (= Double/NEGATIVE_INFINITY (rt Double/NEGATIVE_INFINITY))))
  (testing "char"
    (is (= \a (rt \a)))
    (is (= \é (rt \é)))))

(deftest strings-and-bytes
  (is (= "" (rt "")))
  (is (= "hello" (rt "hello")))
  (is (= "héllo — 世界" (rt "héllo — 世界")))
  (let [s (apply str (repeat 100 "a"))]
    (is (= s (rt s))))
  (let [bs (byte-array [1 2 3 4 5])
        r  (rt bs)]
    (is (java.util.Arrays/equals bs ^bytes r))))

(deftest idents
  (is (= :foo    (rt :foo)))
  (is (= :ns/foo (rt :ns/foo)))
  (is (= 'foo    (rt 'foo)))
  (is (= 'ns/foo (rt 'ns/foo))))

(deftest collections
  (is (= []           (rt [])))
  (is (= [1 2 3]      (rt [1 2 3])))
  (is (= [[1] [2]]    (rt [[1] [2]])))
  (is (= {}           (rt {})))
  (is (= {:a 1 :b 2}  (rt {:a 1 :b 2})))
  (is (= #{}          (rt #{})))
  (is (= #{1 2 3}     (rt #{1 2 3})))
  (is (= '(1 2 3)     (rt '(1 2 3)))))

(deftest interning
  (testing "keyword repeated across map"
    (let [m {:a 1 :b 2 :c 3}
          v [m m m m]
          enc (hako/encode v)
          dec (hako/decode enc)]
      (is (= v dec))
      ;; symref should keep repeated encoding compact — sanity: 4 copies
      ;; of {:a :b :c} would be 3*4 = 12 keyword payloads inline.
      (is (< (alength enc)
             (+ 20 (* 4 (+ 6 6 6)))))))
  (testing "sym-table survives across map keys"
    (is (= {:x 1 :y 2 :z 3} (rt {:x 1 :y 2 :z 3})))))

(deftest specials
  (let [u (UUID/randomUUID)]
    (is (= u (rt u))))
  (let [t (Instant/now)]
    (is (= t (rt t)))))

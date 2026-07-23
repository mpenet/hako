(ns s-exp.hako.m7-test
  "Custom comparator coercion opt-in."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(deftest custom-comparator-strict-default
  (testing "sorted-set-by throws by default"
    (is (thrown-with-msg? Exception #"custom comparator"
                          (hako/encode (sorted-set-by #(compare %2 %1) 1 2 3)))))
  (testing "sorted-map-by throws by default"
    (is (thrown-with-msg? Exception #"custom comparator"
                          (hako/encode (sorted-map-by #(compare %2 %1) :a 1 :b 2))))))

(deftest custom-comparator-coerced
  (testing "sorted-set-by coerced to default-cmp sorted-set"
    (let [s (sorted-set-by #(compare %2 %1) 3 1 2)
          enc (hako/encode s {:coerce-custom-comparator true})
          r (hako/decode enc)]
      (is (instance? clojure.lang.PersistentTreeSet r))
      (is (= [1 2 3] (vec r)))                 ; natural order, comparator lost
      (is (= #{1 2 3} (set r)))))
  (testing "sorted-map-by coerced"
    (let [m (sorted-map-by #(compare %2 %1) :b 2 :a 1 :c 3)
          enc (hako/encode m {:coerce-custom-comparator true})
          r (hako/decode enc)]
      (is (instance? clojure.lang.PersistentTreeMap r))
      (is (= [:a :b :c] (keys r)))
      (is (= {:a 1 :b 2 :c 3} (into {} r))))))

(ns s-exp.meep.m1-test
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.meep.core :as meep]
            [s-exp.meep.ext :as ext]))

(defn- rt
  ([v] (meep/decode (meep/encode v)))
  ([v opts] (meep/decode (meep/encode v opts))))

;; -- Bignumeric -------------------------------------------------------------

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

;; -- Sorted collections -----------------------------------------------------

(deftest sorted-colls
  (testing "sorted-set default cmp"
    (let [s (sorted-set 3 1 2 4)
          r (rt s)]
      (is (= s r))
      (is (instance? clojure.lang.PersistentTreeSet r))
      (is (= [1 2 3 4] (vec r)))))
  (testing "sorted-map default cmp"
    (let [m (sorted-map :b 2 :a 1 :c 3)
          r (rt m)]
      (is (= m r))
      (is (instance? clojure.lang.PersistentTreeMap r))
      (is (= [[:a 1] [:b 2] [:c 3]] (vec r)))))
  (testing "custom comparator fails to encode"
    (is (thrown? Exception
                 (meep/encode (sorted-set-by #(compare %2 %1) 1 2 3))))
    (is (thrown? Exception
                 (meep/encode (sorted-map-by #(compare %2 %1) :a 1 :b 2))))))

;; -- Queue -------------------------------------------------------------------

(deftest queue
  (let [q (into clojure.lang.PersistentQueue/EMPTY [1 2 3 4])
        r (rt q)]
    (is (instance? clojure.lang.PersistentQueue r))
    (is (= (seq q) (seq r)))))

;; -- Records -----------------------------------------------------------------

(defrecord Point [x y])
(defrecord Line [start end label])
(defrecord UnregisteredForSure [x])

(ext/register-record! Point)
(ext/register-record! Line)

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
          enc (meep/encode xs)
          r (meep/decode enc)]
      (is (= xs r))
      ;; classname stored once + 99 symrefs; each Point is 3 fields
      ;; (record tag + symref + count-tier + 2 vals). Ballpark check.
      (is (< (alength enc) 1500))))
  (testing "unregistered class fails"
    (is (thrown? Exception (meep/encode (->UnregisteredForSure 1))))))

;; -- Metadata ---------------------------------------------------------------

(deftest metadata
  (testing "metadata dropped by default"
    (let [v (with-meta [1 2 3] {:tag :vec})
          r (rt v)]
      (is (= [1 2 3] r))
      (is (nil? (meta r)))))
  (testing "metadata preserved with :meta? true"
    (let [v (with-meta [1 2 3] {:tag :vec :extra 42})
          r (rt v {:meta? true})]
      (is (= [1 2 3] r))
      (is (= {:tag :vec :extra 42} (meta r)))))
  (testing "no metadata → no wrap, no change"
    (let [v [1 2 3]
          r (rt v {:meta? true})]
      (is (= [1 2 3] r)))))

;; -- Mixed --------------------------------------------------------------------

(deftest mixed
  (let [v {:points [(->Point 1 2) (->Point 3 4)]
           :ratio 22/7
           :big 1234567890123456789012345N
           :sorted (sorted-set 5 3 1 2 4)}
        r (rt v)]
    (is (= v r))
    (is (instance? clojure.lang.PersistentTreeSet (:sorted r)))
    (is (instance? Point (first (:points r))))))

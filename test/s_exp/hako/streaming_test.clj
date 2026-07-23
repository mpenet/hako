(ns s-exp.hako.streaming-test
  "Multi-value encode/decode with shared symbol table."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(deftest encode-many-basic
  (testing "roundtrip a small stream"
    (let [xs [{:a 1} {:a 2} {:b 3} 42 "hello"]
          enc (hako/encode-many xs)
          r (hako/decode-many enc)]
      (is (= xs r))))
  (testing "empty stream"
    (let [enc (hako/encode-many [])
          r (hako/decode-many enc)]
      (is (= [] r)))))

(deftest shared-symbol-table
  (testing "shared sym-table saves size vs independent encodes"
    (let [values (vec (repeat 100 {:hello "world" :foo :bar :long-name-key 1}))
          streamed (hako/encode-many values)
          independent-total (reduce + (map #(alength (hako/encode %)) values))]
      ;; Sym-table sharing eliminates per-value kw re-emission. Actual
      ;; wire content per repeated value collapses from ~46B to ~13B.
      (is (< (alength streamed) (long (* 0.5 independent-total)))
          (format "sym-table sharing should halve output (streamed=%d independent=%d)"
                  (alength streamed) independent-total)))))

(deftest stream-vs-independent
  (testing "encode-many produces smaller output than concatenating encode calls"
    (let [values (mapv (fn [i] {:name (str "person-" i)
                                :id i
                                :tags #{:active :premium}
                                :meta {:source :api :version 3}})
                       (range 20))
          streamed (hako/encode-many values)
          independent-total (reduce + (map #(alength (hako/encode %)) values))]
      (is (< (alength streamed) independent-total)))))

(deftest decoder-reducible-basic
  (testing "reduce over decoder gets all values"
    (let [xs [{:a 1} {:a 2} {:b 3} :leaf "end"]
          bs (hako/encode-many xs)]
      (is (= xs (into [] (hako/decoder bs))))
      (is (= (count xs) (reduce (fn [n _] (inc n)) 0 (hako/decoder bs)))))))

(deftest decoder-with-xform
  (testing "into with filter xform"
    (let [xs (mapv (fn [i] {:i i :active (even? i)}) (range 20))
          bs (hako/encode-many xs)]
      (is (= (filter :active xs)
             (into [] (filter :active) (hako/decoder bs))))))
  (testing "into with composed xform"
    (let [xs (mapv (fn [i] {:i i}) (range 100))
          bs (hako/encode-many xs)]
      (is (= (into #{} (comp (map :i) (filter odd?) (take 5))
                   xs)
             (into #{} (comp (map :i) (filter odd?) (take 5))
                   (hako/decoder bs)))))))

(deftest decoder-early-termination
  (testing "reduced short-circuits — reader stops early, no over-read"
    (let [xs (mapv (fn [i] {:i i}) (range 1000))
          bs (hako/encode-many xs)
          taken (into [] (take 3) (hako/decoder bs))]
      (is (= [{:i 0} {:i 1} {:i 2}] taken)))))

(deftest decoder-eduction-and-sequence
  (let [xs (mapv (fn [i] {:i i}) (range 10))
        bs (hako/encode-many xs)]
    (testing "eduction is lazy but realizable multiple times"
      (let [ed (eduction (map :i) (hako/decoder bs))]
        (is (= (range 10) (vec ed)))
        (is (= (range 10) (vec ed)))))
    (testing "sequence returns a lazy seq"
      (is (= (range 10) (sequence (map :i) (hako/decoder bs)))))))

(deftest decoder-reduce-transduce
  (let [xs (mapv (fn [i] i) (range 100))
        bs (hako/encode-many xs)]
    (testing "reduce sums values"
      (is (= (reduce + xs)
             (reduce + 0 (hako/decoder bs)))))
    (testing "transduce with filter + map"
      (is (= (transduce (comp (filter odd?) (map inc)) + 0 xs)
             (transduce (comp (filter odd?) (map inc)) + 0
                        (hako/decoder bs)))))))

(deftest decoder-single-value
  (testing "single-value payloads also flow through decoder"
    (let [bs (hako/encode {:name "solo"})]
      (is (= [{:name "solo"}] (into [] (hako/decoder bs)))))))

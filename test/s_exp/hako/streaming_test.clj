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

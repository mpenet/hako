(ns s-exp.hako.edge-cases-test
  "Structural edge cases: empty containers, deep nesting, large
  symref tables, MemorySegment source, mixed types across boundaries."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.lang.foreign MemorySegment)))

(defn- rt [v] (hako/decode (hako/encode v)))

(deftest empty-containers
  (testing "empty vector"
    (is (= [] (rt [])))
    (is (vector? (rt []))))
  (testing "empty list"
    (is (= '() (rt '())))
    (is (empty? (rt '()))))
  (testing "empty set"
    (is (= #{} (rt #{})))
    (is (set? (rt #{}))))
  (testing "empty map"
    (is (= {} (rt {}))))
  (testing "empty sorted-set"
    (is (= (sorted-set) (rt (sorted-set)))))
  (testing "empty sorted-map"
    (is (= (sorted-map) (rt (sorted-map)))))
  (testing "empty queue"
    (let [q clojure.lang.PersistentQueue/EMPTY
          r (rt q)]
      (is (empty? r))
      (is (instance? clojure.lang.PersistentQueue r))))
  (testing "empty string"
    (is (= "" (rt ""))))
  (testing "empty byte-array"
    (let [bs (byte-array 0)
          r (rt bs)]
      (is (bytes? r))
      (is (zero? (alength ^bytes r))))))

(deftest deep-nesting
  (testing "50-level nested vector"
    (let [deep (reduce (fn [inner _] [inner]) :leaf (range 50))]
      (is (= deep (rt deep)))))
  (testing "50-level nested map"
    (let [deep (reduce (fn [inner i] {(keyword (str "k" i)) inner})
                       :leaf (range 50))]
      (is (= deep (rt deep)))))
  (testing "mixed nesting: vec of maps of sets of vecs"
    (let [v (mapv (fn [i]
                    {:i i
                     :tags #{:a :b :c}
                     :children (mapv #(vector % (* 2 %)) (range 5))})
                  (range 30))]
      (is (= v (rt v))))))

(deftest many-unique-keywords
  (testing "1000 unique keys — symref table survives"
    (let [m (into {} (for [i (range 1000)]
                       [(keyword (str "kw-" i)) i]))
          r (rt m)]
      (is (= m r))
      (is (= 1000 (count r))))))

(deftest repeated-keyword-in-vec
  (testing "same kw repeated 500 times — symref compression"
    (let [v (vec (repeat 500 :marker))
          enc (hako/encode v)]
      (is (= v (hako/decode enc)))
      ;; First occurrence + 499 symrefs. Rough upper bound.
      (is (< (alength enc) (+ 20 20 (* 499 2)))))))

(deftest memory-segment-source
  (testing "decode accepts MemorySegment directly, not just byte[]"
    (let [payload {:a 1 :b [2 3 4]}
          bs (hako/encode payload)
          seg (MemorySegment/ofArray bs)]
      (is (= payload (hako/decode seg))))))

(deftest arena-backed-segment-roundtrip
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [payload {:x [1 2 3] :y "hello"}
          seg (hako/encode-to-segment arena payload)]
      (is (= payload (hako/decode seg))))))

(deftest reader-reset-preserves-handler
  (testing "reusable Reader keeps extension handler across .reset"
    (let [rd (hako/reader (byte-array 0))
          payloads [{:a 1} :leaf "s" [1 2 3]]]
      (doseq [v payloads]
        (let [bs (hako/encode v)]
          (is (= v (hako/decode-into! rd bs)))))))
  (testing "options apply per-call via decode-into!"
    (let [rd (hako/reader (byte-array 0))
          plain-bs (hako/encode (byte-array [1 2 3]))
          r-copy (hako/decode-into! rd plain-bs)
          r-slice (hako/decode-into! rd plain-bs {:zero-copy true})]
      (is (bytes? r-copy))
      (is (instance? MemorySegment r-slice)))))

(deftest writer-reuse-preserves-handler
  (testing "reusable Writer keeps handler across .reset"
    (with-open [wr (hako/writer 512)]
      (doseq [v [{:a 1} :leaf "s" [1 2 3]]]
        (let [seg (hako/encode-into! wr v)
              n (.byteSize seg)
              arr (byte-array n)]
          (MemorySegment/copy seg java.lang.foreign.ValueLayout/JAVA_BYTE 0 arr 0 n)
          (is (= v (hako/decode arr))))))))

(deftest zero-length-payload-decode-throws
  (testing "empty byte-array throws with clear error"
    (is (thrown? Exception (hako/decode (byte-array 0))))))

(ns s-exp.hako.m6-test
  "Regression tests for review-flagged issues."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.time Instant)))

;; -- P0.1: no infinite ensure() loop for pathological sizes ------------------
;;
;; Cannot allocate ~2^62 bytes to trigger the real overflow path in a test, so
;; verify the guard rejects an oversized write via API contract instead. The
;; guard fires from ensure() before any allocation.

(deftest capacity-overflow-throws
  (testing "modest encode still works alongside the guard"
    (let [xs (long-array [1 2 3])]
      (is (= (class xs) (class (hako/decode (hako/encode xs)))))))
  (testing "ensureForTesting rejects negative n"
    (let [w (com.s_exp.hako.Writer. 64)]
      (try
        (is (thrown-with-msg? Exception #"max buffer capacity"
                              (.ensureForTesting w -1)))
        (finally (.close w)))))
  (testing "ensureForTesting rejects n exceeding MAX_CAP"
    (let [w (com.s_exp.hako.Writer. 64)]
      (try
        (is (thrown-with-msg? Exception #"max buffer capacity"
                              (.ensureForTesting w Long/MAX_VALUE)))
        (finally (.close w))))))

;; -- P0.2: u64-tier count guard rejects >Integer/MAX_VALUE -------------------

(defn- craft-u64-vec-header
  "Build envelope + `<vec tag with u64 tier><u64 count LE>` bytes claiming
  a count of `n` (no actual elements)."
  ^bytes [^long n]
  (let [buf (byte-array (+ 5 1 8))]
    (aset-byte buf 0 (unchecked-byte 0x48))
    (aset-byte buf 1 (unchecked-byte 0x41))
    (aset-byte buf 2 (unchecked-byte 0x4B))
    (aset-byte buf 3 (unchecked-byte 0x4F))
    (aset-byte buf 4 (unchecked-byte 0x00))
    (aset-byte buf 5 (unchecked-byte 0x7F))
    (dotimes [i 8]
      (aset-byte buf (+ 6 i) (unchecked-byte (bit-and (bit-shift-right n (* 8 i)) 0xFF))))
    buf))

(deftest u64-count-guard
  (testing "count > Integer/MAX_VALUE is rejected"
    (is (thrown-with-msg?
         Exception #"vector count exceeds Integer/MAX_VALUE"
         (hako/decode (craft-u64-vec-header (long (inc Integer/MAX_VALUE))))))))

;; -- Negative Instant (pre-1970) roundtrip -----------------------------------

(deftest instant-negative
  (let [t (Instant/ofEpochSecond -1000000 500000)  ; Feb 1970 minus stuff
        r (hako/decode (hako/encode t))]
    (is (= t r)))
  (let [t (Instant/ofEpochSecond -62135596800 0)   ; year 0001
        r (hako/decode (hako/encode t))]
    (is (= t r))))

;; -- encode-to-segment lifetime + roundtrip ----------------------------------

(deftest encode-to-segment-roundtrip
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [v {:a 1 :b [1 2 3]}
          seg (hako/encode-to-segment arena v)
          r (hako/decode seg)]
      (is (= v r))
      (is (pos? (.byteSize seg))))))

(ns s-exp.hako.m5-test
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako Reader Writer)
           (java.lang.foreign MemorySegment)
           (java.net URI)))

;; ---------------------------------------------------------------------------
;; user-tags: register java.net.URI as a length-prefixed extension
;; ---------------------------------------------------------------------------

(def uri-tag-id 0x10000001)

(ext/register-user-tag!
 uri-tag-id
 URI
 (fn write-uri [^Writer w ^URI u]
   (.writeString w (str u)))
 (fn read-uri [^Reader r]
   ;; read the encoded string from the payload
   (let [tag-byte (.getByte r)
         low (bit-and tag-byte 0x0F)
         payload-len (.readTierPayload r (int low))]
     (URI. (.getString r (int payload-len))))))

(deftest user-tag-roundtrip
  (testing "single URI"
    (let [u (URI. "https://example.com/path?q=1")
          r (hako/decode (hako/encode u))]
      (is (instance? URI r))
      (is (= u r))))
  (testing "URI inside a collection"
    (let [v [(URI. "https://a") (URI. "https://b")]
          r (hako/decode (hako/encode v))]
      (is (= v r))
      (is (every? #(instance? URI %) r)))))

(deftest tolerant-unknown-tag
  (let [u (URI. "https://example.com")
        enc (hako/encode u)]
    (testing "encoded frame is short (5 header + payload)"
      (is (< (alength enc) 60)))
    (testing "strict decode with no registration throws"
      ;; simulate unregistered state by decoding into a fresh JVM view: use
      ;; a fake id we'll never register.
      (with-redefs [ext/user-tag-reader (constantly nil)]
        (is (thrown-with-msg? Exception #"unknown user-tag"
                              (hako/decode enc)))))
    (testing "tolerant decode with no registration yields TaggedValue"
      (with-redefs [ext/user-tag-reader (constantly nil)]
        (let [r (hako/decode enc {:tolerant? true})]
          (is (ext/tagged-value? r))
          (is (= uri-tag-id (:ext r)))
          (is (instance? MemorySegment (:bytes r))))))))

;; ---------------------------------------------------------------------------
;; homogeneous vector auto-pack
;; ---------------------------------------------------------------------------

(deftest pack-homogeneous
  (testing "vector of Long packs to prim-longs (round-trips as long[])"
    (let [v (vec (range 100))
          packed (hako/encode v {:pack-homogeneous? true})
          r (hako/decode packed)]
      (is (= (class (long-array 0)) (class r)))
      (is (java.util.Arrays/equals (long-array v) ^longs r))))
  (testing "pack wins on size for large ints"
    (let [v (vec (repeat 100 Long/MAX_VALUE))
          plain (hako/encode v)
          packed (hako/encode v {:pack-homogeneous? true})]
      (is (< (alength packed) (alength plain)))))
  (testing "vector of Double packs"
    (let [v (mapv double (range 100))
          packed (hako/encode v {:pack-homogeneous? true})
          r (hako/decode packed)]
      (is (= (class (double-array 0)) (class r)))
      (is (java.util.Arrays/equals (double-array v) ^doubles r))))
  (testing "mixed vector falls back to normal encoding"
    (let [v [1 2 "three" 4]
          packed (hako/encode v {:pack-homogeneous? true})
          r (hako/decode packed)]
      (is (= v r))))
  (testing "opt off keeps mixed & homogeneous alike"
    (let [v [1 2 3]
          plain (hako/encode v)
          r (hako/decode plain)]
      (is (= v r))
      (is (instance? clojure.lang.IPersistentVector r)))))

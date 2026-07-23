(ns s-exp.hako.user-tag-test
  "User-tag registration, roundtrip, and :tolerate-unknown-tags."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]
            [s-exp.hako.ext :as ext])
  (:import (com.s_exp.hako Reader Writer)
           (java.lang.foreign MemorySegment)
           (java.net URI)))

(def uri-tag-id 0x10000001)

(ext/register-user-tag!
 uri-tag-id
 URI
 (fn write-uri [^Writer w ^URI u]
   (.writeString w (str u)))
 (fn read-uri [^Reader r]
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
      (with-redefs [ext/user-tag-reader (constantly nil)]
        (is (thrown-with-msg? Exception #"unknown user-tag"
                              (hako/decode enc)))))
    (testing "tolerant decode with no registration yields TaggedValue"
      (with-redefs [ext/user-tag-reader (constantly nil)]
        (let [r (hako/decode enc {:tolerate-unknown-tags true})]
          (is (ext/tagged-value? r))
          (is (= uri-tag-id (:ext r)))
          (is (instance? MemorySegment (:bytes r))))))))

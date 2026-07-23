(ns s-exp.hako.corrupted-test
  "Verify decoder rejects malformed input without corrupting state."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.util Arrays)))

(defn- envelope [] (byte-array [0x48 0x41 0x4B 0x4F 0x00]))

(defn- concat-bytes ^bytes [& parts]
  (let [total (long (reduce + (map alength parts)))
        out (byte-array total)]
    (loop [off 0 [p & more] parts]
      (if p
        (do (System/arraycopy ^bytes p 0 out off (alength ^bytes p))
            (recur (+ off (alength ^bytes p)) more))
        out))))

(deftest bad-magic
  (testing "envelope with wrong magic bytes"
    (let [bs (byte-array [0x66 0x6F 0x6F 0x21 0x00])]
      (is (thrown-with-msg? Exception #"bad magic" (hako/decode bs))))))

(deftest unsupported-version
  (testing "envelope with non-zero version"
    (let [bs (byte-array [0x48 0x41 0x4B 0x4F 0x05])]
      (is (thrown-with-msg? Exception #"unsupported version" (hako/decode bs))))))

(deftest truncated-envelope
  (testing "envelope shorter than 5 bytes throws cleanly"
    (doseq [n [0 1 2 3 4]]
      (let [bs (byte-array n)]
        (is (thrown? Exception (hako/decode bs))
            (str "n=" n))))))

(deftest truncated-value
  (testing "truncated string length prefix"
    ;; envelope + string tag u32 (14=0x4E) then only 2 bytes of the 4-byte length
    (let [bs (concat-bytes (envelope) (byte-array [0x4E 0x01 0x02]))]
      (is (thrown-with-msg? Exception #"unexpected end" (hako/decode bs)))))
  (testing "string tag with length > remaining bytes"
    ;; envelope + string tag u8 (0x4C) + length 0x10 (16) but no payload
    (let [bs (concat-bytes (envelope) (byte-array [0x4C 0x10]))]
      (is (thrown-with-msg? Exception #"unexpected end" (hako/decode bs))))))

(deftest bad-tier-code
  (testing "tag byte with reserved / unused nibble in float major"
    ;; float major 0x20 with low nibble 5 (reserved)
    (let [bs (concat-bytes (envelope) (byte-array [0x25]))]
      (is (thrown-with-msg? Exception #"unknown float subtype" (hako/decode bs))))))

(deftest unknown-major
  (testing "reserved major 0xB0 (record) currently unassigned to a Reader path"
    ;; Major 0xB is reserved. Reader should throw a clear error.
    (let [bs (concat-bytes (envelope) (byte-array [0xB0]))]
      (is (thrown-with-msg? Exception #"unknown major" (hako/decode bs))))))

(deftest symref-past-table
  (testing "symref referencing an index that was never populated"
    ;; envelope + M_SYMREF (0xC0) with inline low nibble = 5 (asks for index 5)
    (let [bs (concat-bytes (envelope) (byte-array [0xC5]))]
      ;; ArrayList.get(5) on an empty list throws IndexOutOfBounds; decoder
      ;; propagates as a runtime exception. Verify some exception fires.
      (is (thrown? Exception (hako/decode bs))))))

(deftest unknown-extension
  (testing "unknown built-in extension subtype (spec bug, strict throw)"
    ;; Reserve subtypes 9-14 for future. 0xE9 is unassigned.
    (let [bs (concat-bytes (envelope) (byte-array [0xE9]))]
      (is (thrown-with-msg? Exception #"unknown extension" (hako/decode bs))))))

(deftest unknown-user-tag-strict
  (testing "unregistered user-tag with strict decode throws"
    ;; envelope + 0xEF + u32 id + u32 tier code (0x0E) + u32 length 0 + no payload
    (let [id-bs (byte-array [0x00 0x01 0x02 0x03])
          bs (concat-bytes (envelope)
                           (byte-array [0xEF])
                           id-bs
                           (byte-array [0x0E])                ; TIER_U32
                           (byte-array [0x00 0x00 0x00 0x00])  ; u32 length = 0
                           )]
      (is (thrown-with-msg? Exception #"unknown user-tag"
                            (hako/decode bs))))))

(deftest unknown-user-tag-tolerant
  (testing "unregistered user-tag with :tolerant? true returns TaggedValue"
    (let [id-bs (byte-array [0x00 0x01 0x02 0x03])
          payload-bs (byte-array [0xAA 0xBB])
          bs (concat-bytes (envelope)
                           (byte-array [0xEF])
                           id-bs
                           (byte-array [0x0E])
                           (byte-array [0x02 0x00 0x00 0x00])  ; u32 length = 2
                           payload-bs)
          r (hako/decode bs {:tolerant? true})]
      (is (some? r)))))

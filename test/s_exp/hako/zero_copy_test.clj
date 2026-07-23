(ns s-exp.hako.zero-copy-test
  ":zero-copy decode returns MemorySegment slices for byte payloads."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.lang.foreign MemorySegment ValueLayout)))

(deftest zero-copy-bytes
  (let [payload (byte-array (range 256))
        enc (hako/encode payload)]
    (testing "default decode returns byte[]"
      (let [r (hako/decode enc)]
        (is (bytes? r))
        (is (java.util.Arrays/equals payload ^bytes r))))
    (testing "zero-copy decode returns MemorySegment slice"
      (let [seg-src (MemorySegment/ofArray enc)
            r (hako/decode seg-src {:zero-copy true})]
        (is (instance? MemorySegment r))
        (is (= 256 (.byteSize ^MemorySegment r)))
        (dotimes [i 256]
          (is (= (byte (aget ^bytes payload i))
                 (.get ^MemorySegment r ValueLayout/JAVA_BYTE i))))))))

(deftest zero-copy-nested-in-map
  (let [payload {:blob (byte-array [10 20 30 40])
                 :meta "info"}
        enc (hako/encode payload)
        r (hako/decode (MemorySegment/ofArray enc) {:zero-copy true})]
    (is (instance? MemorySegment (:blob r)))
    (is (= 4 (.byteSize ^MemorySegment (:blob r))))
    (is (= "info" (:meta r)))))

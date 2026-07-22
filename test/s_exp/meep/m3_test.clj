(ns s-exp.meep.m3-test
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.meep.core :as meep])
  (:import (java.lang.foreign MemorySegment ValueLayout)))

(deftest zero-copy-bytes
  (let [payload (byte-array (range 256))
        enc (meep/encode payload)]
    (testing "default decode returns byte[]"
      (let [r (meep/decode enc)]
        (is (bytes? r))
        (is (java.util.Arrays/equals payload ^bytes r))))
    (testing "zero-copy decode returns MemorySegment slice"
      (let [seg-src (MemorySegment/ofArray enc)
            r (meep/decode seg-src {:zero-copy? true})]
        (is (instance? MemorySegment r))
        (is (= 256 (.byteSize ^MemorySegment r)))
        (dotimes [i 256]
          (is (= (byte (aget ^bytes payload i))
                 (.get ^MemorySegment r ValueLayout/JAVA_BYTE i))))))))

(deftest zero-copy-nested-in-map
  (let [payload {:blob (byte-array [10 20 30 40])
                 :meta "info"}
        enc (meep/encode payload)
        r (meep/decode (MemorySegment/ofArray enc) {:zero-copy? true})]
    (is (instance? MemorySegment (:blob r)))
    (is (= 4 (.byteSize ^MemorySegment (:blob r))))
    (is (= "info" (:meta r)))))

(ns s-exp.meep.m2-test
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.meep.core :as meep])
  (:import (com.s_exp.meep Writer)
           (java.lang.foreign MemorySegment ValueLayout)))

(defn- seg->bytes [^MemorySegment s]
  (let [n (.byteSize s)
        arr (byte-array n)]
    (MemorySegment/copy s ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(deftest prim-long-array
  (let [xs (long-array [1 2 3 -1 Long/MAX_VALUE Long/MIN_VALUE 0])
        r (meep/decode (meep/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^longs r))))

(deftest prim-double-array
  (let [xs (double-array [1.0 -2.5 3.14 Double/MAX_VALUE Double/MIN_VALUE])
        r (meep/decode (meep/encode xs))]
    (is (= (class xs) (class r)))
    (is (java.util.Arrays/equals xs ^doubles r))))

(deftest reusable-writer
  (let [wr (meep/writer 1024)]
    (try
      (testing "sequential encode-into! reuses buffer"
        (doseq [v [1 [1 2 3] {:a 1} "hello" (long-array [1 2 3])]]
          (let [seg (meep/encode-into! wr v)
                bs (seg->bytes seg)]
            (is (or (= v (meep/decode bs))
                    (and (bytes? bs)
                         (java.util.Arrays/equals ^longs v
                                                  ^longs (meep/decode bs))))))))
      (testing "state resets between encodes (sym table cleared)"
        (let [seg1 (seg->bytes (meep/encode-into! wr :foo))
              seg2 (seg->bytes (meep/encode-into! wr :foo))]
          (is (java.util.Arrays/equals seg1 seg2))))
      (finally (.close wr)))))

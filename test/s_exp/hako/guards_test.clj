(ns s-exp.hako.guards-test
  "Capacity + count-overflow guards, arena roundtrips."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

;; -- Writer.ensure() capacity overflow guard --------------------------------

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

;; -- u32-tier count guard rejects > Integer/MAX_VALUE -----------------------
;; (tier 15 on container majors is the indefinite marker, not u64)

(defn- craft-u32-vec-header
  "Build envelope + `<vec tag with u32 tier><u32 count LE>` bytes claiming
  a count of `n` (no actual elements)."
  ^bytes [^long n]
  (let [buf (byte-array (+ 5 1 4))]
    (aset-byte buf 0 (unchecked-byte 0x48))
    (aset-byte buf 1 (unchecked-byte 0x41))
    (aset-byte buf 2 (unchecked-byte 0x4B))
    (aset-byte buf 3 (unchecked-byte 0x4F))
    (aset-byte buf 4 (unchecked-byte 0x00))
    (aset-byte buf 5 (unchecked-byte 0x7E))
    (dotimes [i 4]
      (aset-byte buf (+ 6 i) (unchecked-byte (bit-and (bit-shift-right n (* 8 i)) 0xFF))))
    buf))

(deftest u32-count-guard
  (testing "count > Integer/MAX_VALUE is rejected"
    (is (thrown-with-msg?
         Exception #"vector count exceeds Integer/MAX_VALUE"
         (hako/decode (craft-u32-vec-header (long (inc Integer/MAX_VALUE))))))))

;; -- indefinite-length containers --------------------------------------------

(defn- envelope+ ^bytes [& byte-vals]
  (byte-array (concat [0x48 0x41 0x4B 0x4F 0x00] (map unchecked-byte byte-vals))))

(deftest indefinite-containers
  (testing "empty indefinite vector: 0x7F + break"
    (is (= [] (hako/decode (envelope+ 0x7F 0xFA)))))
  (testing "indefinite vector with two inline uints"
    (is (= [1 2] (hako/decode (envelope+ 0x7F 0x01 0x02 0xFA)))))
  (testing "indefinite map: one kv pair"
    (is (= {1 2} (hako/decode (envelope+ 0xAF 0x01 0x02 0xFA)))))
  (testing "indefinite set"
    (is (= #{1 2} (hako/decode (envelope+ 0x9F 0x01 0x02 0xFA)))))
  (testing "indefinite list"
    (is (= '(1 2) (hako/decode (envelope+ 0x8F 0x01 0x02 0xFA)))))
  (testing "break outside a container throws"
    (is (thrown-with-msg? Exception #"break tag outside"
                          (hako/decode (envelope+ 0xFA)))))
  (testing "break at map value position throws"
    (is (thrown-with-msg? Exception #"break tag outside"
                          (hako/decode (envelope+ 0xAF 0x01 0xFA 0xFA)))))
  (testing "truncated indefinite container throws"
    (is (thrown-with-msg? Exception #"unexpected end"
                          (hako/decode (envelope+ 0x7F 0x01)))))
  (testing "lazy seq roundtrips through indefinite form"
    (let [v (map inc (range 100))]
      (is (= (apply list v) (hako/decode (hako/encode v))))))
  (testing "empty lazy seq roundtrips"
    (is (= '() (hako/decode (hako/encode (map inc [])))))))

(deftest container-count-header-guard
  (testing "writeListHeader rejects counts exceeding u32"
    (let [w (com.s_exp.hako.Writer. 64)]
      (try
        (is (thrown-with-msg? Exception #"container count exceeds u32"
                              (.writeListHeader w (inc 0xFFFFFFFF))))
        (finally (.close w))))))

;; -- list count vs remaining-bytes guard -------------------------------------

(defn- craft-u32-list-header
  "Build envelope + `<list tag with u32 tier><u32 count LE>` bytes claiming
  a count of `n` (no actual elements)."
  ^bytes [^long n]
  (let [buf (byte-array (+ 5 1 4))]
    (aset-byte buf 0 (unchecked-byte 0x48))
    (aset-byte buf 1 (unchecked-byte 0x41))
    (aset-byte buf 2 (unchecked-byte 0x4B))
    (aset-byte buf 3 (unchecked-byte 0x4F))
    (aset-byte buf 4 (unchecked-byte 0x00))
    (aset-byte buf 5 (unchecked-byte 0x8E))
    (dotimes [i 4]
      (aset-byte buf (+ 6 i) (unchecked-byte (bit-and (bit-shift-right n (* 8 i)) 0xFF))))
    buf))

(deftest list-count-prealloc-guard
  (testing "list count exceeding remaining bytes fails fast, no Object[n] prealloc"
    ;; count fits in an int (passes checkCount) but the message holds no
    ;; elements — without the need(n) guard this attempts a ~2 GB alloc.
    (is (thrown-with-msg?
         Exception #"unexpected end of message"
         (hako/decode (craft-u32-list-header (- Integer/MAX_VALUE 8)))))))

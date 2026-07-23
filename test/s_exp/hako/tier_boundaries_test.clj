(ns s-exp.hako.tier-boundaries-test
  "Exercise every size-tier code (inline / u8 / u16 / u32) at exact
  boundary values across every major type that uses size-tiers.

  Payloads that would require u64 tiers (~2 GB+) are exercised via
  crafted headers only, not real allocations."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(defn- rt [v] (hako/decode (hako/encode v)))

(deftest uint-tier-boundaries
  (testing "inline uint (0..11)"
    (doseq [n [0 1 5 10 11]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u8 tier boundary (12..255)"
    (doseq [n [12 100 254 255]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u16 tier boundary (256..65535)"
    (doseq [n [256 1000 65534 65535]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u32 tier boundary (65536..2^32-1)"
    (doseq [n [65536 100000 (dec (bit-shift-left 1 32))]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u64 tier (Long-representable)"
    (doseq [n [(bit-shift-left 1 32)
               (bit-shift-left 1 62)
               Long/MAX_VALUE]]
      (is (= n (rt n)) (str "n=" n)))))

(deftest sint-tier-boundaries
  (testing "small negatives (inline zig-zag)"
    (doseq [n [-1 -2 -3 -4 -5 -6]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u8 tier (zig-zag) — larger negatives"
    (doseq [n [-100 -128 -255]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u16 tier"
    (doseq [n [-256 -32768 -65535]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u32 tier"
    (doseq [n [-65536 (- Integer/MIN_VALUE) Integer/MIN_VALUE]]
      (is (= n (rt n)) (str "n=" n))))
  (testing "u64 tier — Long.MIN_VALUE zig-zag hits full u64"
    (is (= Long/MIN_VALUE (rt Long/MIN_VALUE)))))

(deftest string-length-tier-boundaries
  (testing "inline (0..11 bytes)"
    (doseq [n [0 1 5 11]]
      (let [s (apply str (repeat n "a"))]
        (is (= s (rt s)) (str "len=" n)))))
  (testing "u8 tier (12..255)"
    (doseq [n [12 100 254 255]]
      (let [s (apply str (repeat n "a"))]
        (is (= s (rt s)) (str "len=" n)))))
  (testing "u16 tier"
    (doseq [n [256 1000 65535]]
      (let [s (apply str (repeat n "a"))]
        (is (= s (rt s)) (str "len=" n)))))
  (testing "u32 tier — 70k chars"
    (let [n 70000
          s (apply str (repeat n "a"))]
      (is (= s (rt s))))))

(deftest bytes-length-tier-boundaries
  (testing "each tier"
    (doseq [n [0 11 12 255 256 65535 65536]]
      (let [bs (byte-array (range n))
            r (rt bs)]
        (is (java.util.Arrays/equals bs ^bytes r) (str "len=" n))))))

(deftest vector-count-tier-boundaries
  (testing "inline (0..11)"
    (doseq [n [0 1 11]]
      (is (= (vec (range n)) (rt (vec (range n)))))))
  (testing "u8 tier"
    (doseq [n [12 255]]
      (is (= (vec (range n)) (rt (vec (range n)))))))
  (testing "u16 tier"
    (is (= (vec (range 300)) (rt (vec (range 300)))))
    (is (= (vec (range 65535)) (rt (vec (range 65535)))))))

(deftest map-count-tier-boundaries
  (testing "each tier"
    (doseq [n [0 1 8 11 12 255 256 500]]
      (let [m (into {} (for [i (range n)] [i (str "v" i)]))]
        (is (= m (rt m)) (str "n=" n))))))

(deftest keyword-payload-length-boundary
  (testing "inline keyword name (up to 11 bytes)"
    (is (= :abcdefghijk (rt :abcdefghijk))))
  (testing "u8 tier keyword name"
    (let [long-name (apply str (repeat 200 "x"))
          kw (keyword long-name)]
      (is (= kw (rt kw)))))
  (testing "u16 tier keyword name"
    (let [long-name (apply str (repeat 300 "y"))
          kw (keyword long-name)]
      (is (= kw (rt kw))))))

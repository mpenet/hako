(ns split-bench
  "Fast encode-only bench for isolating writer-path changes. 6 payloads
  most sensitive to writer changes, hako one-shot + reused encode only.

  Run: clj -M:bench -m split-bench [payload ...]

  ~30s per full pass."
  (:require [criterium.core :as c]
            [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)
           (java.lang.foreign MemorySegment ValueLayout)))

(def payloads
  {:small-map      {:name "Alice" :age 30 :city "Paris"}
   :mixed          [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]
   :nested-map     (into {} (for [i (range 50)]
                              [(keyword (str "k" i))
                               {:idx i :val (* i i) :tag :leaf}]))
   :ns-map         (into {} (for [i (range 50)]
                              [(keyword (str "ns" (mod i 5)) (str "k" i))
                               (keyword (str "other") (str "v" i))]))
   :vec-of-strings (vec (map #(str "item-" %) (range 100)))
   :string-100     (apply str (repeat 100 "x"))
   :string-10k     (apply str (repeat 10000 "x"))})

(def opts
  {:target-execution-time (long (* 300 1e6))
   :warmup-jit-period     (long (* 300 1e6))
   :samples               3})

(defn- seg->bytes ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(defn- fmt [seconds]
  (let [ns (* seconds 1e9)]
    (cond
      (< ns 1000)    (format "%7.1f ns" ns)
      (< ns 1000000) (format "%7.2f µs" (/ ns 1000.0))
      :else          (format "%7.2f ms" (/ ns 1000000.0)))))

(defn- run [label f]
  (let [result (c/quick-benchmark* f opts)
        mean (first (:mean result))]
    (println (format "  %-16s %s" label (fmt mean)))
    mean))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)]
    (doseq [[label v] selected]
      (println "===" label "===")
      (let [wr (hako/writer 4096)
            enc (hako/encode v)
            rd (hako/reader enc)]
        (try
          (run "hako enc"        (fn [] (hako/encode v)))
          (run "hako⤾ enc"       (fn [] (seg->bytes (hako/encode-into! wr v))))
          (run "hako dec"        (fn [] (hako/decode enc {:cache-idents true})))
          (run "hako⤾ dec"       (fn [] (hako/decode-into! rd enc {:cache-idents true})))
          (finally (.close wr)))))))

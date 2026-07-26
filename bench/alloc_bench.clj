(ns alloc-bench
  "Per-op allocation profile via
  com.sun.management.ThreadMXBean.getThreadAllocatedBytes. Not JMH,
  but the same signal: how many bytes each encode call allocates.

  Run: clj -M:bench -m alloc-bench [payload ...]"
  (:require [s-exp.hako :as hako]
            [taoensso.nippy :as nippy])
  (:import (com.s_exp.hako Writer)
           (com.sun.management ThreadMXBean)
           (java.lang.foreign MemorySegment ValueLayout)
           (java.lang.management ManagementFactory)))

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
   :string-10k     (apply str (repeat 10000 "x"))
   :long-array-1k  (long-array (range 1000))})

(defn- ^ThreadMXBean tmx []
  (let [b (ManagementFactory/getThreadMXBean)]
    (when-not (instance? ThreadMXBean b)
      (throw (ex-info "com.sun.management.ThreadMXBean not available" {})))
    b))

(defn- allocated-bytes ^long [^ThreadMXBean b]
  (.getThreadAllocatedBytes b (.getId (Thread/currentThread))))

(defn- seg->bytes ^bytes [^MemorySegment seg]
  (let [n (.byteSize seg)
        arr (byte-array n)]
    (MemorySegment/copy seg ValueLayout/JAVA_BYTE 0 arr 0 n)
    arr))

(defn- profile
  "Warm the JIT, then measure allocation delta over N iterations.
  Returns bytes-per-op."
  [label f n]
  (dotimes [_ 5000] (f))
  (System/gc)
  (let [b (tmx)
        start (allocated-bytes b)
        _ (dotimes [_ n] (f))
        end (allocated-bytes b)
        total (- end start)
        per-op (/ (double total) n)]
    (println (format "  %-16s %10.1f B/op   (%d ops, %d total)"
                     label per-op n total))
    per-op))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)
        n 100000]
    (doseq [[label v] selected]
      (println "===" label "===")
      (let [wr (hako/writer 4096)
            enc (hako/encode v)
            rd (hako/reader enc)
            nippy-enc (nippy/freeze v)
            nippy-fast-enc (nippy/fast-freeze v)]
        (try
          (profile "hako enc"        (fn [] (hako/encode v))              n)
          (profile "hako-seg enc→byt" (fn [] (seg->bytes
                                              (hako/encode-into! wr v)))   n)
          (profile "hako-seg enc"    (fn [] (hako/encode-into! wr v))     n)
          (profile "nippy enc"       (fn [] (nippy/freeze v))             n)
          (profile "nippy-fast enc"  (fn [] (nippy/fast-freeze v))        n)
          (profile "hako dec"        (fn [] (hako/decode enc
                                                         {:cache-idents true}))       n)
          (profile "hako-seg dec"    (fn [] (hako/decode-into! rd enc
                                                               {:cache-idents true})) n)
          (profile "nippy dec"       (fn [] (nippy/thaw nippy-enc))       n)
          (profile "nippy-fast dec"  (fn [] (nippy/fast-thaw nippy-fast-enc)) n)
          (finally (.close wr)))))))

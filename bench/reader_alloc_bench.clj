(ns reader-alloc-bench
  "Per-op alloc profile for decode paths. Isolates hotspots by
  payload shape. Aim: identify where reader alloc actually goes."
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Reader)
           (com.sun.management ThreadMXBean)
           (java.lang.foreign MemorySegment)
           (java.lang.management ManagementFactory)))

(def payloads
  {:small-map      {:name "Alice" :age 30 :city "Paris"}
   :mixed          [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]
   :nested-map     (into {} (for [i (range 50)]
                              [(keyword (str "k" i))
                               {:idx i :val (* i i) :tag :leaf}]))
   :ns-map         (into {} (for [i (range 50)]
                              [(keyword (str "ns" (mod i 5)) (str "k" i))
                               (keyword "other" (str "v" i))]))
   :vec-of-strings (vec (map #(str "item-" %) (range 100)))
   :vec-of-longs-1k (vec (range 1000))
   :list-1k         (apply list (range 1000))
   :set-100         (into #{} (map #(str "s-" %) (range 100)))
   :long-array-1k   (long-array (range 1000))
   :double-array-1k (double-array (range 1000))
   :string-100      (apply str (repeat 100 "x"))
   :string-10k      (apply str (repeat 10000 "x"))
   :deep-nested    (reduce (fn [acc _] {:a acc}) {} (range 20))
   :fat-map        (into {} (for [i (range 200)]
                              [(keyword (str "k" i)) i]))})

(defn- ^ThreadMXBean tmx []
  (ManagementFactory/getThreadMXBean))

(defn- allocated-bytes ^long [^ThreadMXBean b]
  (.getThreadAllocatedBytes b (.getId (Thread/currentThread))))

(defn- alloc-per-op [f iters]
  (dotimes [_ 5000] (f))
  (System/gc)
  (let [b (tmx)
        start (allocated-bytes b)
        _ (dotimes [_ iters] (f))
        end (allocated-bytes b)]
    (/ (double (- end start)) iters)))

(defn -main [& args]
  (let [selected (if (seq args)
                   (select-keys payloads (map keyword args))
                   payloads)
        iters 50000
        rd (hako/reader (byte-array 0))]
    (println (format "%-20s %12s %12s %12s"
                     "payload" "fresh-rd" "reused-rd" "size(B)"))
    (println (apply str (repeat 60 "-")))
    (doseq [[label v] selected]
      (let [enc (hako/encode v)
            size (alength enc)
            fresh (alloc-per-op #(hako/decode enc {:cache-idents true}) iters)
            reused (alloc-per-op #(hako/decode-into! rd enc {:cache-idents true}) iters)]
        (println (format "%-20s %12.1f %12.1f %12d"
                         (name label) fresh reused size))))))

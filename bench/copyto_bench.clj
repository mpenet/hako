(ns copyto-bench
  "Bench encode-into! (returns MemorySegment) vs encode-into-buffer!
  (zero-alloc copy into caller's byte[] or ByteBuffer)."
  (:require [s-exp.hako :as hako])
  (:import (com.s_exp.hako Writer)
           (com.sun.management ThreadMXBean)
           (java.lang.management ManagementFactory)
           (java.nio ByteBuffer)))

(def payloads
  {:small-map      {:name "Alice" :age 30 :city "Paris"}
   :mixed          [1 :kw "str" {:a 1 :b [2 3 4]} #{:x :y :z} 3.14]
   :nested-map     (into {} (for [i (range 50)]
                              [(keyword (str "k" i))
                               {:idx i :val (* i i) :tag :leaf}]))
   :long-array-1k  (long-array (range 1000))})

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

(defn -main [& _]
  (let [wr ^Writer (hako/writer 8192)
        dst-heap (byte-array 16384)
        dst-bb-heap (ByteBuffer/allocate 16384)
        dst-bb-direct (ByteBuffer/allocateDirect 16384)
        iters 100000]
    (println (format "%-20s %14s %14s %14s %14s"
                     "payload" "encode-into!" "→byte[]" "→bb-heap" "→bb-direct"))
    (println (apply str (repeat 80 "-")))
    (doseq [[label v] payloads]
      (let [seg-alloc  (alloc-per-op #(hako/encode-into! wr v) iters)
            arr-alloc  (alloc-per-op #(hako/encode-into-buffer! wr dst-heap v) iters)
            bb-h-alloc (alloc-per-op #(do (.clear dst-bb-heap)
                                          (hako/encode-into-buffer! wr dst-bb-heap v))
                                     iters)
            bb-d-alloc (alloc-per-op #(do (.clear dst-bb-direct)
                                          (hako/encode-into-buffer! wr dst-bb-direct v))
                                     iters)]
        (println (format "%-20s %14.1f %14.1f %14.1f %14.1f"
                         (name label) seg-alloc arr-alloc bb-h-alloc bb-d-alloc))))))

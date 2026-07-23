(ns s-exp.hako.concurrency-test
  "Verify the JVM-global keyword / symbol caches used by :cache-idents?
  are safe under concurrent decode from many threads."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(defn- decode-many-times
  "Decode `bs` `iters` times on `n-threads` in parallel. Collect all
  decoded values into a set. Returns {:results set :count total}."
  [bs iters n-threads]
  (let [total (* iters n-threads)
        futures (repeatedly n-threads
                            #(future
                               (into []
                                     (repeatedly iters
                                                 (fn [] (hako/decode bs {:cache-idents? true}))))))]
    (mapv deref futures)))

(deftest cache-concurrent-decode
  (testing "concurrent decode with :cache-idents? produces equal + interned keywords"
    (let [payload {:some-key "value" :other :leaf :ns/qualified 42}
          bs (hako/encode payload)
          decoded-blocks (decode-many-times bs 200 8)
          all-decoded (into #{} cat decoded-blocks)]
      (is (= #{payload} all-decoded))
      ;; Keyword.intern guarantees identity across all interned lookups.
      ;; Verify decoded keywords across threads all share identity with
      ;; the canonical instance.
      (let [canonical :some-key
            decoded-kws (map (fn [decoded-map]
                               (first (filter #(= canonical %)
                                              (keys decoded-map))))
                             (mapcat identity decoded-blocks))]
        (is (every? #(identical? canonical %) decoded-kws)
            "all decoded :some-key must be the interned canonical instance")))))

(deftest cache-race-first-write
  (testing "first-sighting race — many threads decode unique kw sim"
    (let [payloads (mapv (fn [i] {(keyword (str "race-kw-" i)) i})
                         (range 100))
          bss (mapv hako/encode payloads)
          n-threads 16
          futures (repeatedly n-threads
                              #(future
                                 (mapv (fn [bs]
                                         (hako/decode bs {:cache-idents? true}))
                                       bss)))
          results (mapv deref futures)]
      (is (every? (fn [thread-results] (= payloads thread-results)) results)
          "each thread must see all payloads decode correctly"))))

(deftest cache-off-baseline
  (testing "cache-off decode is also thread-safe (baseline)"
    (let [payload (into {} (for [i (range 20)] [(keyword (str "k" i)) i]))
          bs (hako/encode payload)
          futures (repeatedly 8 #(future (mapv (fn [_] (hako/decode bs))
                                               (range 200))))
          results (mapv deref futures)]
      (is (every? (fn [thread-results]
                    (every? #(= payload %) thread-results))
                  results)))))

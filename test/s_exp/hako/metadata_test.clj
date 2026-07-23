(ns s-exp.hako.metadata-test
  "Metadata preservation via :preserve-meta opt."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako]))

(defn- rt
  ([v] (hako/decode (hako/encode v)))
  ([v opts] (hako/decode (hako/encode v opts))))

(deftest metadata-default-dropped
  (let [v (with-meta [1 2 3] {:tag :vec})
        r (rt v)]
    (is (= [1 2 3] r))
    (is (nil? (meta r)))))

(deftest metadata-preserve-opt-in
  (let [v (with-meta [1 2 3] {:tag :vec :extra 42})
        r (rt v {:preserve-meta true})]
    (is (= [1 2 3] r))
    (is (= {:tag :vec :extra 42} (meta r)))))

(deftest metadata-no-meta-noop
  (let [v [1 2 3]
        r (rt v {:preserve-meta true})]
    (is (= [1 2 3] r))))

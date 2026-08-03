(ns s-exp.hako.native-ext-test
  "Roundtrip tests for native ext-tag types: Pattern, URI, Duration, Period."
  (:require [clojure.test :refer [deftest is testing]]
            [s-exp.hako :as hako])
  (:import (java.net URI)
           (java.time Duration LocalDate LocalDateTime LocalTime
                      OffsetDateTime Period ZonedDateTime ZoneId ZoneOffset)
           (java.util.regex Pattern)))

(deftest regex-pattern
  (testing "plain pattern"
    (let [p #"^foo.*bar$"
          r (hako/decode (hako/encode p))]
      (is (instance? Pattern r))
      (is (= (.pattern p) (.pattern ^Pattern r)))
      (is (= (.flags p) (.flags ^Pattern r)))))
  (testing "pattern with flags preserved"
    (let [p (Pattern/compile "hello" (bit-or Pattern/CASE_INSENSITIVE
                                             Pattern/MULTILINE
                                             Pattern/DOTALL))
          r (hako/decode (hako/encode p))]
      (is (= (.pattern p) (.pattern ^Pattern r)))
      (is (= (.flags p) (.flags ^Pattern r)))))
  (testing "empty pattern"
    (let [p #""
          r (hako/decode (hako/encode p))]
      (is (= "" (.pattern ^Pattern r))))))

(deftest uri
  (testing "https URI"
    (let [u (URI. "https://example.com/path?q=1#frag")
          r (hako/decode (hako/encode u))]
      (is (instance? URI r))
      (is (= u r))))
  (testing "opaque URI"
    (let [u (URI. "mailto:foo@bar.com")
          r (hako/decode (hako/encode u))]
      (is (= u r))))
  (testing "URI inside a collection"
    (let [v {:home (URI. "https://a") :api (URI. "https://b/api")}
          r (hako/decode (hako/encode v))]
      (is (= v r)))))

(deftest duration
  (testing "positive"
    (let [d (Duration/ofSeconds 100 100)
          r (hako/decode (hako/encode d))]
      (is (= d r))))
  (testing "zero"
    (is (= Duration/ZERO (hako/decode (hako/encode Duration/ZERO)))))
  (testing "negative"
    (let [d (Duration/ofSeconds -42 -500)
          r (hako/decode (hako/encode d))]
      (is (= d r)))))

(deftest period
  (testing "roundtrip"
    (let [p (Period/of 1 2 3)
          r (hako/decode (hako/encode p))]
      (is (= p r))))
  (testing "zero"
    (is (= Period/ZERO (hako/decode (hako/encode Period/ZERO)))))
  (testing "negative components"
    (let [p (Period/of -5 0 -30)
          r (hako/decode (hako/encode p))]
      (is (= p r)))))

(deftest local-date
  (doseq [d [(LocalDate/of 2020 1 1)
             (LocalDate/of 1969 12 31)
             LocalDate/MIN
             LocalDate/MAX]]
    (is (= d (hako/decode (hako/encode d))))))

(deftest local-time
  (doseq [t [(LocalTime/of 13 14 15 500000000)
             LocalTime/MIDNIGHT
             LocalTime/MAX]]
    (is (= t (hako/decode (hako/encode t))))))

(deftest local-date-time
  (doseq [dt [(LocalDateTime/of 2020 1 1 13 14 15 500000000)
              LocalDateTime/MIN
              LocalDateTime/MAX]]
    (is (= dt (hako/decode (hako/encode dt))))))

(deftest zoned-date-time
  (testing "region zone"
    (let [zdt (ZonedDateTime/of 2020 6 15 12 30 45 123456789 (ZoneId/of "Europe/Zurich"))
          r (hako/decode (hako/encode zdt))]
      (is (= zdt r))))
  (testing "UTC"
    (let [zdt (ZonedDateTime/of 2020 1 1 0 0 0 0 (ZoneId/of "UTC"))
          r (hako/decode (hako/encode zdt))]
      (is (= zdt r))))
  (testing "DST-ambiguous local time (fall-back overlap) keeps its instant"
    ;; 2020-10-25 02:30 CET happens twice in Europe/Zurich; both offsets
    ;; must roundtrip to the same instant.
    (let [early (-> (ZonedDateTime/of 2020 10 25 2 30 0 0 (ZoneId/of "Europe/Zurich"))
                    (.withEarlierOffsetAtOverlap))
          late  (.withLaterOffsetAtOverlap early)]
      (is (= early (hako/decode (hako/encode early))))
      (is (= late (hako/decode (hako/encode late))))))
  (testing "inside a collection"
    (let [v [(ZonedDateTime/of 2020 1 1 0 0 0 0 (ZoneId/of "Asia/Tokyo"))]
          r (hako/decode (hako/encode v))]
      (is (= v r)))))

(deftest offset-date-time
  (doseq [odt [(OffsetDateTime/of 2020 6 15 12 30 45 123456789 (ZoneOffset/ofHours 5))
               (OffsetDateTime/of 2020 1 1 0 0 0 0 ZoneOffset/UTC)
               (OffsetDateTime/of 1969 12 31 23 59 59 999999999 (ZoneOffset/ofHoursMinutes -9 -30))]]
    (is (= odt (hako/decode (hako/encode odt))))))

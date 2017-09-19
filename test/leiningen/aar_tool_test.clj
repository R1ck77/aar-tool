(ns leiningen.aar-tool-test
  (:require [clojure.test :refer :all]
            [leiningen.aar-tool :as aar]
            [hara.io.watch]
            [hara.common.watch :as watch])
  (:use [leiningen.core.main :only [info abort debug warn]]
        [clojure.java.shell :only [sh]]
        [clojure.xml :as xml]
        [clojure.java.io :as io]
        [couchgames.utils.zip :as czip]))



(deftest test-absolutize-paths-selectively
  (testing "isn't bothered by set items not in the map"
    (is (= {} (aar/absolutize-paths-selectively {} #{:a :b :c}))))
  (testing "ignores items not in the set"
    (let [weird-map {:a nil :b 42 :c "something" :d "else"}]
      (is (= weird-map (aar/absolutize-paths-selectively weird-map #{})))))
  )

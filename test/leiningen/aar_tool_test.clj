(ns leiningen.aar-tool-test
  (:require [clojure.test :refer :all]
            [leiningen.aar-tool :as aar]
            [hara.io.watch]
            [hara.common.watch :as watch])
  (:import [java.io File])
  (:use [leiningen.core.main :only [info abort debug warn]]
        [clojure.java.shell :only [sh]]
        [clojure.xml :as xml]
        [clojure.java.io :as io]
        [couchgames.utils.zip :as czip]))


(deftest test-convert-path-to-absolute
  (testing "converts an empty string to the current directory"
    (is (= (.getAbsolutePath (File. ""))
           (aar/convert-path-to-absolute ""))))
  (testing "normalizes relative paths"
    (is (= (aar/convert-path-to-absolute "foo/../bar/baz")
           (aar/convert-path-to-absolute "foo/a/b/c/../../../../bar/baz"))))
  (testing "normalizes even absolute paths"
    (is (= "/foo/bar/baz"
           (aar/convert-path-to-absolute "/foo/bar/../bar/baz")))))

(deftest test-absolutize-paths-selectively
  (testing "isn't bothered by set items not in the map"
    (is (= {} (aar/absolutize-paths-selectively {} #{:a :b :c}))))
  (testing "ignores items not in the set"
    (let [weird-map {:a nil :b 42 :c "something" :d "else"}]
      (is (= weird-map (aar/absolutize-paths-selectively weird-map #{})))))
  (testing "map items in the set are filtered through convert-path-to-absolute"
    (with-redefs [aar/convert-path-to-absolute (fn [_] :filtered)]
      (is (= {:a :filtered :b :not-filtered}
             (aar/absolutize-paths-selectively {:a "some path", :b :not-filtered}
                                               #{:a}))))))

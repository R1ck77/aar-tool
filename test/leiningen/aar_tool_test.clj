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

(deftest test-all-directories?
  (testing "all-directories? just check whether the base path is a directory if no other dir is present"
    (with-redefs [aar/is-directory? #(= % :dir)]
      (is (aar/all-directories? :dir))
      (is (not (aar/all-directories? :not-dir)))))
  (testing "all-directories? returns false if either the base or any child path is not a directory"
    (with-redefs [aar/is-directory? #(not  (.contains (str %) ":not-dir"))]
      (is (aar/all-directories? :dir :dir :dir :dir :dir :dir))
      (is (not (aar/all-directories? :not-dir :dir :dir)))
      (is (not (aar/all-directories? :dir :not-dir :dir)))
      (is (not (aar/all-directories? :dir :dir :not-dir))))))

(deftest test-get-android-home
  (testing "get-android-home returns the content of ANDROID_HOME if is-sdk-location?"
    (with-redefs [aar/get-env (fn [x] x)]
      (is (= "ANDROID_HOME" (aar/get-android-home)))))
    (testing "get-android-home prints an error and exits if ANDROID_HOME is not defined"
      (let [abort-called (atom nil)]
        (with-redefs [aar/get-env (constantly nil)
                      leiningen.core.main/abort (fn [message] (reset! abort-called message))]
          (aar/get-android-home)
          (is @abort-called)))))

(deftest test-check-jar-file
  (testing "returns the argument if it's readable"
    (with-redefs [aar/readable-file? (constantly true)]
      (is (= :argument (aar/check-jar-file :argument)))))
  (testing "aborts with a message if the argument it's not a readable file"
    (let [abort-called (atom false)]
      (with-redefs [aar/readable-file? (constantly false)                  
                    leiningen.core.main/abort (fn [ & _] (reset! abort-called true))]
        (aar/check-jar-file (clojure.java.io/file "/tmp/candidate.jar"))
        (is @abort-called)))))

(deftest test-get-android-jar-location
  (testing "calls check-jar-file on the result of get-android-jar-location, but as a path"
    (with-redefs [aar/android-jar-file (fn [ & args ] (vec args))
                  aar/check-jar-file #(conj % :check-jar-file)]
      (is (= [:sdk :version :check-jar-file]
             (aar/get-android-jar-location :sdk :version))))))




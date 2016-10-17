(ns leiningen.android_development
  (:use [leiningen.core.main :only [info abort debug warn]]
        [clojure.java.shell :only [sh]]
        [clojure.xml :as xml]
        [clojure.java.io :as io]
        [couchgames.utils.zip :as czip])
  (:require [hara.io.watch]
            [hara.common.watch :as watch]))

(def aar-build-dir "aar")
(def android-manifest-name "AndroidManifest.xml")
(def expected-jar-name "classes.jar")
(def r-txt "R.txt")

(defn- get-arguments [project xs]
  "Read the arguments from the project, fail if any is missing"
  (debug "Ensuring that the project arguments are there")
  (reduce (fn [m v]
            (if (contains? project v)
              (assoc m v (get project v))
              (abort (str "Unable to find " v " in project.clj!"))))
          {}
          xs))

(defn- check-is-directory!
  "Check whether the specified path is a directory

If create-if-missing is set to true, the function will try to fix that, no solution if the file exists and is not a directory"
  ([path] (check-is-directory! path false))
  ([path create-if-missing]
   (debug (str "Checking for \"" path "\" existance"))
   (let [fpath (java.io.File. path)]
     (if (not (.isDirectory fpath))
       (if (and create-if-missing (not (.exists fpath)))
         (do
           (debug (str "The path \"" path "\" is not present: creating…"))
           (.mkdirs fpath)
           (check-is-directory! path false))
         (abort (str "The path \"" path "\" doesn't exist, or is not a directory")))))))

(defn- path-from-dirs [base & elements]
  "creating a Path in java 7 from clojure is messy"
  (java.nio.file.Paths/get base (into-array String elements)))

(defn- copy-file [source-path dest-path]
  "Kudos to this guy: https://clojuredocs.org/clojure.java.io/copy"
  (io/copy (io/file source-path) (io/file dest-path)))

(defn- run-aapt [aapt destpath manifest android-jar res]
  "Run the aapt command with the parameters required to generate a R.txt file

This will also generate a R.java file in destpath/src"
  (let [src-path (.toString (path-from-dirs destpath "src"))]
    (check-is-directory! src-path true)
    (let [res (sh aapt
                "package"
                "--output-text-symbols" destpath
                "-v"
                "-f"
                "-m"
                "-M" manifest
                "-I" android-jar
                "-S" res
                "-J" src-path)]
      (if (not= 0 (:exit res))
        (abort (str "Invocation of aapt failed with error: \"" (:err res) "\""))))))

(defn- zip-contents [work-path manifest res-dir jar]
  "Create a .aar library in a temporary location. Returns the path"
  (let [
        full-r-path (.toString (path-from-dirs work-path r-txt))
        aar-file (.toString (path-from-dirs work-path "library.aar"))
        res-files (files-in-dir res-dir (.getParent (java.io.File. res-dir)))
        zip-arguments (vec (concat [aar-file [jar expected-jar-name] [full-r-path r-txt] [manifest android-manifest-name]]
                                   res-files))]
   (debug "invoking the zip command with arguments:" zip-arguments)
   (if (nil? (apply czip/zip-files zip-arguments))
     (abort "Unable to compress" jar full-r-path "and" manifest "into" aar-file)
     aar-file)))


(defn- convert-path-to-absolute [path]
  (.toString (.normalize (.toAbsolutePath (.toPath (java.io.File. path))))))

(defn- absolutize-paths [m s]
  (reduce (fn [res [key value]]
            (assoc res
                   key (if (contains? s key)
                         (convert-path-to-absolute value)
                         value)))            
          {}
          m))

(defn- move [source destination]
  (debug "Moving" source "to" destination)
  (try
    (.delete (java.io.File. destination))
    (catch Exception e (abort "Unable to remove the destination file" destination "to make space for the resulting aar")))
  (.renameTo (java.io.File. source) (java.io.File. destination)))

(defn- check-arguments [params]
  (let [manifest (:android-manifest params)
        aapt-file (java.io.File. (:aapt params))]
    (if (not (.canExecute aapt-file)) (abort (str (.toString aapt-file) " is not a valid executable")))
    (if (not (.exists (java.io.File. (:android-jar params)))) (abort (str (:android-jar params) " is not a valid file")))
    (if (not= "res" (.getName (java.io.File. (:res params)))) (abort "The :res option must point to a directory named \"res\""))
    (if (not= android-manifest-name (.getName (java.io.File. manifest))) (abort "The :res option must point to a directory named \"" android-manifest-name "\""))
    (if (not (.exists (java.io.File. manifest))) (abort (str "The file \"" manifest "\" does not exist")))
    (if (not= (:aot params) [:all]) (abort (str ":aot :all must be specified in project.clj!" (:android-jar params))))))

(defn create-aar 
  "Create a AAR library suitable for Android integration"
  [project arguments]
  (let [my-args (absolutize-paths
                 (get-arguments project [:android-jar :aapt :aar-name :aot :res :source-paths :target-path :android-manifest])
                 #{:android-jar :aapt :aar-name :res :target-path :android-manifest})]
    (check-arguments my-args)
    ;;; TODO/FIXME: get the jar name in a more robust way (use the jar task function directly?)
    (let [jar-path (second (first (leiningen.core.main/apply-task "jar" project [])))
          tmp-path (.toString (path-from-dirs (:target-path my-args) aar-build-dir))]
      (debug "The jar is: " jar-path)
      (debug "The aar compilation will be made in " tmp-path)
      (check-is-directory! (:res my-args))
      (check-is-directory! tmp-path true)
      (run-aapt (:aapt my-args)
                tmp-path
                (:android-manifest my-args)
                (:android-jar my-args)
                (:res my-args))
      (let [aar-location (zip-contents tmp-path
                                       (:android-manifest my-args)
                                       (:res my-args)
                                       jar-path)]
        
        (shutdown-agents)
        (move aar-location (:aar-name my-args))
        (info "Created" (:aar-name my-args))))))

(defn watch-res 
  "Update the contents of the R.java file when a :res file changes"
  [project & args]
  (println "Args:    " (:res project)))

(defn android_development
  "Functions for android development"
  {:subtasks [#'create-aar #'watch-res]}
  [project & args]
  (case (first args) 
    nil (info "An action must be provided")
    "aar" (create-aar project (rest args))
    "watch-res" (watch-res project (rest args))
    (abort "Unknown option")))

(ns leiningen.aar-tool
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
(def non-res-files [#".*[.]swp$" #".*~"])

(defn- all-directories? [base & childs]
  "Returns true if and only if both base and all its childs are directories"
  (and
   (.isDirectory (io/file base))
   (reduce (fn [acc value]
             (and acc (.isDirectory (io/file base value))))
           true
           childs)))

(defn- get-aapt-location [sdk version]
  "Return the path of aapt for a specific version of the build tools in the sdk

Throw a runtime exception if not found or not executable"
  (let [aapt (io/file (apply str (interpose java.io.File/separator [sdk "build-tools" version "aapt"])))]
    (if (and (.exists aapt) (.canExecute aapt))
      (.getAbsolutePath aapt)
      (throw (RuntimeException. (str "\"" (.getAbsolutePath aapt) "\" doesn't exist, or is not an executable"))))))

(defn- get-sdk-location []
  "Locate the android sdk from the informations at hand.

Uses the ANDROID_HOME env. variable only, at the moment.

Throw a RuntimeException if none can be found"
  (let [home (System/getenv "ANDROID_HOME")]
    (if (all-directories? home "build-tools" "platforms" "tools")
      home
      (throw (RuntimeException. "Unable to find the android sdk (is ANDROID_HOME correctly set?)")))))

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

;;;; TODO/FIXME: The "src" string is completely bogus and will break
;;;; watch-res if we specify a different source dir in leinigen
(defn- run-aapt [aapt destpath manifest android-jar res]
  "Run the aapt command with the parameters required to generate a R.txt file

This will also generate a R.java file in destpath/src"
  (let [src-path (if (nil? destpath)
                   "src"
                   (.toString (path-from-dirs destpath "src")))]
    
    (check-is-directory! src-path true)
    
    (let [sh-arguments (if (nil? destpath)
                         (list aapt
                               "package"
                               "-f"
                               "-m"
                               "-M" manifest
                               "-I" android-jar
                               "-S" res
                               "-J" src-path)
                         (list aapt
                               "package"
                               "--output-text-symbols" destpath
                               "-f"
                               "-m"
                               "-M" manifest
                               "-I" android-jar
                               "-S" res
                               "-J" src-path))
          res (apply sh sh-arguments)]
      res)))

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

(defn- run-aapt-noisy [& args]
  (let [res (apply run-aapt args)]
    (if (not= 0 (:exit res))
        (abort (str "Invocation of aapt failed with error: \"" (:err res) "\"")))))

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
      (run-aapt-noisy (:aapt my-args)
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

(defn- clear-trailing [char string]
  (apply str (reverse (drop-while (hash-set char) (reverse string)))))

(defn- generate-R-java [aapt manifest android-jar res]
  (let [result (run-aapt aapt nil manifest android-jar res)]
    (info (case (:exit result)
               0 "✓ R.java updated"
               (clear-trailing \newline (str "❌ error with R.java generatino: " ( :err result)))))
    (flush)
    result))

(defn- watch-res-directory [my-args]
  "Update the contents of the R.java file when a :res file changes"
  
  (let [aapt (:aapt my-args)
        manifest (:android-manifest my-args)
        android-jar (:android-jar my-args)
        res (:res my-args)
        dir (:res my-args)
        to-be-excluded? (fn [path]
                          (reduce (fn [acc value] (or acc (re-matches value path)))
                                  false
                                  non-res-files))]
    (generate-R-java aapt manifest android-jar res)
    (watch/add (clojure.java.io/file dir)
               :my-watch
               (fn [obj key prev next]                       
                 (let [path (.toString (second next))]                         
                   (if (not (to-be-excluded? path))
                     (generate-R-java aapt manifest android-jar res))))
               {
                :types #{:create :modify :delete}})))

(defn- watches-for-file? [file]
  "Returns true if the watch listener disappeared

This can actually happen only if the watch sort of stops itself"
  (> (count (watch/list file)) 0))

(defn- blocking-watch [project]
  "Starts a watch on the specific directory, and blocks"
  (let [args (absolutize-paths
              (get-arguments project
                             [:android-jar :aapt :res :source-paths :target-path :android-manifest])
              #{:android-jar :aapt :res :target-path :android-manifest})]
    (watch-res-directory args)
    (loop [f (clojure.java.io/file (:res project))]    
      (if (watches-for-file? f)
        (do
          (Thread/sleep 100)
          (recur f))))))

(defn watch-res
  "Watch the res directory and update the R.java if any file changes

  The updates are applied directly into the src directory"
  [project & args]
  (info "Starting a watch on the res directory…")
  (blocking-watch project))

(defn create-R
  "Create a R.java file with the information in the res directory
  
  The file is created in the src directory"
  [project & args]
  (let [args (absolutize-paths
              (get-arguments project
                             [:android-jar :aapt :res :source-paths :target-path :android-manifest])
              #{:android-jar :aapt :res :target-path :android-manifest})
        aapt (:aapt args)
        manifest (:android-manifest args)
        android-jar (:android-jar args)
        res (:res args)
        dir (:res args)]
    (generate-R-java aapt manifest android-jar res)))


(defn aar-tool
  "Functions for android development"
  {:subtasks [#'create-aar #'watch-res #'create-R]}
  [project & args]
  (case (first args) 
    nil (info "An action must be provided")
    "create-aar" (create-aar project (rest args))
    "watch-res" (watch-res project (rest args))
    "create-R" (create-R project (rest args))
    (abort "Unknown option")))

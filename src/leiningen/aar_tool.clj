(ns leiningen.aar-tool
  (:use [leiningen.core.main :only [info abort debug warn]]
        [clojure.java.shell :only [sh]]
        [clojure.xml :as xml]
        [clojure.java.io :as io]
        [couchgames.utils.zip :as czip])
  (:import [java.io File ByteArrayInputStream])
  (:require [hara.io.watch]
            [hara.common.watch :as watch]
            [robert.hooke]))

(def android-home-env-var "ANDROID_HOME")

(def aar-build-dir "aar")
(def android-manifest-name "AndroidManifest.xml")
(def expected-jar-name "classes.jar")
(def r-txt "R.txt")
(def non-res-files [#".*[.]swp$" #".*~"])

(defn get-package-from-manifest [slurpable-content]  
  (try
    (or (:package (:attrs  (xml/parse (io/input-stream slurpable-content))))
        (throw (RuntimeException. "package attribute not found")))
    (catch Exception e (abort (str "Unable to read the package from the manifest (" (.getMessage e) ")")))))

(defn get-project-package [{manifest :android-manifest}]
  (get-package-from-manifest manifest))

(defn output-directory-from-package [package]
  (let [components (clojure.string/split package #"[.]")]
    (case (count components)
      0 (throw (RuntimeException. "Unexpected number of package components (0)"))
      1 (first components)
      (.toString (java.nio.file.Paths/get (first components) (into-array (rest components)))))))

(defn R-class-file? [file]
  (and (not (.isDirectory file))
       (re-find #"^R([$].+)?[.]class" (.getName file))))

(defn- get-api-level 
  "Return the value of maxSdkVersion or targetSdkVersion or minSdkVersion or 1"
  [manifest-path]
  (let [attrs (:attrs (first (filter #(= (:tag %) :uses-sdk) (:content (xml/parse manifest-path)))))]
    (Integer/valueOf (or (:android:maxSdkVersion attrs)
                          (:android:targetSdkVersion attrs)
                          (:android:minSdkVersion attrs)
                          "1"))))

(defn readable-file? [file]
  (and (.exists file)
       (.canRead file)))

(defn android-jar-file [sdk version]
  (io/file (apply str (interpose File/separator
                                 [sdk "platforms" (str "android-" version) "android.jar"]))))

(defn check-jar-file [file]
  (if (readable-file? file)
    file
    (abort (str "\"" (.getAbsolutePath file) "\" doesn't exist, or is not readable"))))

(defn get-android-jar-location 
  [sdk version]
  (check-jar-file (android-jar-file sdk version)))

(defn- get-all-build-tools
  "Return a sequence of all build-tools versions available"
  [sdk]
  (seq (.list (io/file sdk "build-tools"))))

(defn is-directory? [file]
  (.isDirectory file))

(defn composite-path-is-dir? [base-path child-path]
  (is-directory? (io/file (str base-path File/separator child-path))))

(defn all-directories? 
  "Returns true if and only if both base and all its childs are directories"
  [base-path & child-paths]
  (and
   (is-directory? base-path)
   (reduce (fn [acc child] (and acc (composite-path-is-dir? base-path child)))
           true child-paths)))

(defn- android-home-is-valid? [android-home]
  "Check if the ANDROID_HOME variable points to a valid android-sdk directory

The check assumes that ANDROID_HOME contains a number of directories"
  (apply all-directories? (map io/file  [android-home "build-tools" "platforms" "tools"])))

(defn get-env [var]
  (System/getenv var))

(defn get-android-home []
  (if-let [android-home (get-env android-home-env-var)]
    android-home
    (abort (str "The variable \"" android-home-env-var "\" not set"))))

(defn get-most-recent-aapt-location [sdk version]
  "Validate the sdk and return the highest versioned aapt"
  (if (not (android-home-is-valid? sdk))
    (throw (RuntimeException. "sdk not recognized: is the content of ANDROID_HOME valid?"))
    (let [aapt (io/file (apply str (interpose File/separator [sdk "build-tools" version "aapt"])))]
      (if (and (.exists aapt) (.canExecute aapt))
        (.getAbsolutePath aapt)
        (throw (RuntimeException. (str "\"" (.getAbsolutePath aapt) "\" doesn't exist, or is not an executable")))))))

(defn- get-aapt-location
  "Return the path of aapt for a specific version of the build tools in the sdk using the sdk found with 'get-android-home' and the
highest versioned build tool found with 'get-all-build-tools'.

Throw a runtime exception if not found or not executable"
  ([sdk]
   (get-most-recent-aapt-location sdk (last (sort (get-all-build-tools sdk))))))

(defn- android-jar-from-manifest
  "Return the path of android.jar from the android manifest and the environment"
  [manifest-path]
  (.getAbsolutePath (get-android-jar-location  (get-android-home) (get-api-level manifest-path))))

(defn- get-arguments
  "Read the arguments from the project, fail if any is missing"
  [project xs]
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
   (let [fpath (File. path)]
     (if (not (.isDirectory fpath))
       (if (and create-if-missing (not (.exists fpath)))
         (do
           (debug (str "The path \"" path "\" is not present: creating…"))
           (.mkdirs fpath)
           (check-is-directory! path false))
         (abort (str "The path \"" path "\" doesn't exist, or is not a directory")))))))

(defn- path-from-dirs
  "creating a Path in java 7 from clojure is messy"
  [base & elements]
  (java.nio.file.Paths/get base (into-array String elements)))

(defn- copy-file
  "Kudos to this guy: https://clojuredocs.org/clojure.java.io/copy"
  [source-path dest-path]
  (io/copy (io/file source-path) (io/file dest-path)))

;;;; TODO/FIXME: The "src" string is completely bogus and will break
;;;; watch-res if we specify a different source dir in leinigen
(defn- run-aapt
  "Run the aapt command with the parameters required to generate a R.txt file

  This will also generate a R.java file in destpath/src"
  [aapt destpath sympath manifest android-jar res]
  (let [src-path (if (nil? destpath)
                   (do (warn "Using hard-coded src-java as R.java path") "src-java")
                   destpath)]
    
    (check-is-directory! src-path true)
    
    (let [sh-arguments (if (nil? sympath)
                         (list aapt
                               "packange"
                               "-f"
                               "-m"
                               "-M" manifest
                               "-I" android-jar
                               "-S" res
                               "-J" src-path)
                         (list aapt
                               "package"
                               "--output-text-symbols" sympath
                               "-f"
                               "-m"
                               "-M" manifest
                               "-I" android-jar
                               "-S" res
                               "-J" src-path))
          res (do
                (debug "About to run aapt with arguments: " sh-arguments)
                (apply sh sh-arguments))]
      res)))

(defn- filter-temp
  "Remove from the list of paths/pairs the files ending with ~"
  [xs]
  (filter
   (fn [v]
     (not (re-matches #".*~$" (if (string? v)
                                v
                                (first v))))) xs))

(defn- zip-contents
  "Create a .aar library in a temporary location. Returns the path"
  [work-path manifest res-dir jar]
  (let [
        full-r-path (.toString (path-from-dirs work-path r-txt))
        aar-file (.toString (path-from-dirs work-path "library.aar"))
        res-files (filter-temp (files-in-dir res-dir (.getParent (File. res-dir))))
        zip-arguments (vec (concat [aar-file [jar expected-jar-name] [full-r-path r-txt] [manifest android-manifest-name]]
                                   res-files))]
   (debug "invoking the zip command with arguments:" zip-arguments)
   (if (nil? (apply czip/zip-files zip-arguments))
     (abort "Unable to compress" jar full-r-path "and" manifest "into" aar-file)
     aar-file)))


(defn convert-path-to-absolute [path]
  (-> (File. path)
      .toPath .toAbsolutePath .normalize .toString))

(defn absolutize-paths-selectively [m-options s-keys]
  (into m-options
        (map (fn [[key value]]
               (vector key (convert-path-to-absolute value)))
             (filter #(-> % first s-keys) m-options))))

(defn- move [source destination]
  (debug "Moving" source "to" destination)
  (try
    (.delete (File. destination))
    (catch Exception e (abort "Unable to remove the destination file" destination "to make space for the resulting aar")))
  (.renameTo (File. source) (File. destination)))

(defn- check-arguments [params]
  (let [manifest (:android-manifest params)]
    (if (not= "res" (.getName (File. (:res params)))) (abort "The :res option must point to a directory named \"res\""))
    (if (not= android-manifest-name (.getName (File. manifest))) (abort "The :res option must point to a directory named \"" android-manifest-name "\""))
    (if (not (.exists (File. manifest))) (abort (str "The file \"" manifest "\" does not exist")))
    (if (not= (:aot params) [:all]) (abort (str ":aot :all must be specified in project.clj!")))))

(defn- run-aapt-noisy [& args]
  (let [res (apply run-aapt args)]
    (if (not= 0 (:exit res))
        (abort (str "Invocation of aapt failed with error: \"" (:err res) "\"")))))

(defn wipe-class-prototype [project]
  (info "### About to mangle the file, this is gross 2-girls-one-cup stuff: PLEASE FIX THIS!!!!!")
  (let [r-files (conj (filter #(re-find #"^R[$].*[.]class" (.getName %)) (file-seq (io/file "target/classes/it/couchgames/lib/cjutils"))) (io/file "target/classes/it/couchgames/lib/cjutils/R.class"))]
    (println "r-files:" r-files)
    (dorun (map #(do
                   (println "Deleting" % "in" (.getAbsolutePath (io/file ".")))
                     (.delete %))
                r-files))))

(defn leiningen-task-hook [function task project args]
  (println "About to execute the task" task)
  ;;; Apply the task
  (let [result (function task project args)]
    (println "Task" task "executed")
      ;;; if the task is javac, remove the R*.class after compilation
    (if (= task "compile")
      (do
        (println "About to wipe the extra classes")
        (wipe-class-prototype project)))
    result))

(defn activate-compile-hook []
  (robert.hooke/add-hook #'leiningen.core.main/apply-task #'leiningen-task-hook))

(def create-R)

(defn create-aar 
  "Create a AAR library suitable for Android integration"
  [project arguments]

  (create-R project arguments)
  
  (activate-compile-hook)

  (let [my-args (absolutize-paths-selectively
                 (get-arguments project [:aar-name :aot :res :source-paths :target-path :android-manifest])
                 #{:aar-name :res :target-path :android-manifest})]
    (check-arguments my-args)
    ;;; TODO/FIXME: get the jar name in a more robust way (use the jar task function directly?)
    (let [jar-path (second (first (leiningen.core.main/apply-task "jar" project [])))
          tmp-path (.toString (path-from-dirs (:target-path my-args) aar-build-dir))
          dest-path (.toString (path-from-dirs tmp-path "src"))]
      (debug "The jar is: " jar-path)
      (debug "The aar compilation will be made in " tmp-path)
      (check-is-directory! (:res my-args))
      (check-is-directory! tmp-path true)
      (run-aapt-noisy (get-aapt-location (get-android-home))
                      dest-path
                      tmp-path
                      (:android-manifest my-args)
                      (android-jar-from-manifest (:android-manifest my-args))
                      (:res my-args))
      (let [aar-location (zip-contents tmp-path
                                       (:android-manifest my-args)
                                       (:res my-args)
                                       jar-path)]
        
        (move aar-location (:aar-name my-args))
        (shutdown-agents)
        (info "Created" (:aar-name my-args))))))

(defn- clear-trailing [char string]
  (apply str (reverse (drop-while (hash-set char) (reverse string)))))

(defn- generate-R-java [aapt manifest android-jar res src]
  (let [result (run-aapt aapt src nil manifest android-jar res)]
    (info
     (case (:exit result)
       0 "✓ R.java updated"
       (clear-trailing \newline (str "❌ error with R.java generation: " ( :err result)))))
    (flush)
    result))

(defn- watch-res-directory
  "Update the contents of the R.java file when a :res file changes"
  [my-args]
  
  (let [aapt (get-aapt-location (get-android-home))
        manifest (:android-manifest my-args)
        android-jar (android-jar-from-manifest (:android-manifest my-args))
        res (:res my-args)
        dir (:res my-args)
        java-src (first (:java-src-paths my-args))
        to-be-excluded? (fn [path]
                          (reduce (fn [acc value] (or acc (re-matches value path)))
                                  false
                                  non-res-files))]
    (generate-R-java aapt manifest android-jar res java-src)
    (watch/add (clojure.java.io/file dir)
               :my-watch
               (fn [obj key prev next]                       
                 (let [path (.toString (second next))]                         
                   (if (not (to-be-excluded? path))
                     (generate-R-java aapt manifest android-jar res java-src))))
               {
                :types #{:create :modify :delete}})))

(defn- watches-for-file?
  "Returns true if the watch listener disappeared

This can actually happen only if the watch sort of stops itself"
  [file]
  (> (count (watch/list file)) 0))

(defn- blocking-watch
  "Starts a watch on the specific directory, and blocks"
  [project]
  (let [args (absolutize-paths-selectively
              (get-arguments project
                             [:res :source-paths :target-path :android-manifest])
              #{:res :target-path :android-manifest})]
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
  (let [args (absolutize-paths-selectively
              (get-arguments project
                             [:java-source-paths :res :source-paths :target-path :android-manifest])
              #{:res :target-path :android-manifest})
        aapt (get-aapt-location (get-android-home))
        manifest (:android-manifest args)
        android-jar (android-jar-from-manifest manifest)
        res (:res args)
        dir (:res args)
        java-src (first (:java-source-paths args))]
    (if (nil? java-src)
      (abort "no :java-src-paths specified (at least one is needed)")
      (do
        (info "Using '" java-src "' for the R.java output…")
        (generate-R-java aapt manifest android-jar res java-src)))))

(defn leiningen-test [project & args]
  (activate-compile-hook)
  (leiningen.core.main/apply-task "jar" project [])
  (shutdown-agents))

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

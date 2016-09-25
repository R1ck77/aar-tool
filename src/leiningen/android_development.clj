(ns leiningen.android_development
  (use [leiningen.core.main :only [info abort debug]]
       [clojure.java.shell :only [sh]]
       [clojure.xml :as xml]
       [clojure.java.io :as io]))

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
        (abort (str "Invocation of aapt failed with error: \"" (:err res) "\""))
        (info "Invocation of aapt successful")))))

;;;; TODO/FIXME: this is horrible and unportable. Use the java ZipOutputStream!
(defn- zip-contents [work-path manifest res-dir jar]
  "Create a .aar library in a temporary location. Returns the path"
  (let [cpath (.toString (path-from-dirs work-path expected-jar-name))
        manifest-copy (.toString (path-from-dirs work-path android-manifest-name))
        aar (.toString (path-from-dirs work-path "library.aar"))]
   (debug (str "copying the classes jar into \"" cpath "\""))
   (copy-file jar cpath)
   (debug (str "copying the manifest \"" manifest "\" into \"" manifest-copy "\""))
   (copy-file manifest manifest-copy)
   (debug "invoking the zip command")
   (let [zip-args ["zip" "-r" "-9" aar expected-jar-name r-txt res-dir android-manifest-name :dir work-path]
         res (apply sh zip-args)]
     (debug (str "zip command invoked with arguments: \"" zip-args "\""))
     (if (= (:exit res) 0)
       (do
         (debug (str "AAR library created as \"" aar "\""))
         aar)
       (abort (str "zip failed with error: \"" (:err res) "\" (arguments: \"" zip-args "\""))))))


(defn- convert-path-to-absolute [path]
  (.getAbsolutePath (java.io.File. path)))

(defn- absolutize-paths [m s]
  (reduce (fn [res [key value]]
            (assoc res
                   key (if (contains? s key)
                         (convert-path-to-absolute value)
                         value)))            
          {}
          m))

(defn- create-aar [project arguments]
  (let [my-args (absolutize-paths
                 (get-arguments project [:android-jar :aapt :aar-name :aot :res :source-paths :target-path :android-manifest])
                 #{:android-jar :aapt :aar-name :res :target-path :android-manifest})]
    (if (not= (:aot my-args) [:all])
      (abort (str ":aot :all must be specified in project.clj!" (:android-jar my-args)))
      (do
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
            (debug "create-aar executed")
            ))))))

(defn android_development
  "Create a aar file from a jar"
  [project & args]
  (case (first args) 
    nil (info "An action must be provided")
    "aar" (create-aar project (rest args))
    (abort "Unknown option")))

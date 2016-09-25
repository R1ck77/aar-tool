(ns leiningen.android_development
  (use [leiningen.core.main :only [info abort debug]]
       [clojure.java.shell :only [sh]]
       [clojure.xml :as xml]))

(def aar-build-dir "aar")


(defn- get-arguments [project xs]
  "Read the arguments from the project, fail if any is missing"
  (debug "Ensuring that the project arguments are there")
  (reduce (fn [m v]
            (if (contains? project v)
              (assoc m v (get project v))
              (abort (str "Unable to find " v " in project.clj!"))))
          {}
          xs
          ))

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

(defn- get-package-from-android-manifest [path]
  "Read the manifest at path, and retun the package name

Unused… Created because I misread the 'package' argument in the aapt arguments list :p"
  (:package (:attrs (xml/parse path))))

(defn- path-from-dirs [base & elements]
  "creating a Path in java 7 from clojure is messy"
  (java.nio.file.Paths/get base (into-array String elements)))

(defn- run-aapt [aapt manifest android-jar res compiled-jar destpath]
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
      (shutdown-agents)
      (if (not= 0 (:exit res))
        (abort (str "Invocation of aapt failed with error: \"" (:err res) "\""))
        (info "Invocation of aapt successful")))))

(defn- create-aar [project arguments]
  (let [my-args (get-arguments project [:android-jar :aapt :aar-name :aot :res :source-paths :target-path :android-manifest])]
    (if (not= (:aot my-args) [:all])
      (abort (str ":aot :all must be specified in project.clj!" (:android-jar my-args)))
      (do
        ;;; TODO/FIXME: get the plugin in a sander way here (use the jar function directly?)
        (let [jar-path (second (first (leiningen.core.main/apply-task "jar" project [])))
              tmp-path (.toString (path-from-dirs (:target-path my-args) aar-build-dir))]
          (debug "The jar is: " jar-path)
          (debug "The aar compilation will be made in " tmp-path)
          (check-is-directory! (:res my-args))
          (check-is-directory! tmp-path true)
          (run-aapt (:aapt my-args)                    
                    (:android-manifest my-args)
                    (:android-jar my-args)
                    (:res my-args)
                    jar-path
                    tmp-path)
          (debug "create-aar executed")
          
;;;; # zip the aar file
;;;; echo "zipping the aar content into ${AAR_TEMP_FILE}…"
;;;; rm ${AAR_TEMP_FILE}
;;;; zip -r ${AAR_TEMP_FILE} classes.jar R.txt res AndroidManifest.xml
;;;; mv ${AAR_TEMP_FILE} ${AAR_DESTINATION}
;;;;           
          )))))

(defn android_development
  "Create a aar file from a jar"
  [project & args]
  (case (first args) 
    nil (info "An action must be provided")
    "aar" (create-aar project (rest args))
    (abort "Unknown option")))

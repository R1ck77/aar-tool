(ns leiningen.android_development
  (use [leiningen.core.main :only [info abort]]
       [clojure.java.shell :only [sh]]))


(defn- get-arguments [project xs]
  "Read the arguments from the project, fail if any is missing"
  (info "Ensuring that the project arguments are there")
  (reduce (fn [m v]
            (if (contains? project v)
              (assoc m v (get project v))
              (abort (str "Unable to find " v " in project.clj!"))))
          {}
          xs
          ))

(defn- check-or-create-directory! [path]
  (info "Checking for " path "existance")
  )


(defn- create-aar [project arguments]
  (let [my-args (get-arguments project [:android-jar :aapt :aar-name :aot])]
    (if (not= (:aot my-args) [:all])
      (abort (str ":aot :all must be specified in project.clj!" (:android-jar my-args)))
      (do
        ;;; TODO/FIXME: get the plugin in a sander way here (use the jar function directly?)
        (let [jar-path (second (first (leiningen.core.main/apply-task "jar" project [])))]
          (info "The jar is: " jar-path)
          (check-or-create-directory! "src")
          (check-or-create-directory! "res")
          (info "create-aar executed")
          ;;; RUN AAPT
;;;; # The --output-text-symbols is paramento
;;;; ${AAPT} package \
;;;; 	--output-text-symbols ${DEV_HOME} \
;;;; 	-v \
;;;; 	-f \
;;;; 	-m \
;;;; 	-M ${DEV_HOME}/AndroidManifest.xml \
;;;; 	-I ${ANDROID_JAR} \
;;;; 	-S ${DEV_HOME}/res \
;;;; 	-J ${DEV_HOME}/src \
;;;; 
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

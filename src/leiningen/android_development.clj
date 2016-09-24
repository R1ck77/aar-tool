(ns leiningen.android_development
  (use [leiningen.core.main :only [info]]))

(defn- create-aar [project arguments]
  (info "create-aar executed"))

(defn android_development
  "Create a aar file from a jar"
  [project & args]
  (case (first args) 
    nil (info "An action must be provided")
    "aar" (create-aar project (rest args))
    (info "Unknown option")))

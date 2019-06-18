(defproject my-library "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [android/android "19.0.0"]]
  :repl-options {:init-ns my-library.core}
  :plugins [[aar-tool "0.3.0"]]
  :aar-name "my-library.aar"
  :res "res"
  :android-manifest "AndroidManifest.xml" 
  :omit-source true
  :aot :all
  :javac-options ["-target" "1.7" "-source" "1.7"]
  :java-source-paths ["java-src"])

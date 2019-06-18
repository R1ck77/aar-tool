(ns my-library.core
  (:gen-class
   :name my_library.core.Functions
   :methods [^:static
             [sayHi [android.widget.TextView] void]]))

(defn -sayHi [textView]
  (.setText textView "Hello from Clojure!"))


(ns couchgames.utils.zip
  (import [java.util.zip ZipOutputStream ZipEntry]
          [java.io FileOutputStream File]))

(defn open-zip! ^ZipOutputStream [^String path]
  (ZipOutputStream. (FileOutputStream. path)))

(defn- strip-first-slash [path]
  (apply str (drop-while #{\/} path)))

(defn- ^ZipEntry entry-from-path [^String as-path]
  (doto  (ZipEntry. (strip-first-slash as-path))
    (.setMethod ZipEntry/DEFLATED)
    ;;    (.setComment "This is a comment! Yay!")
    ))

(defn add-to-zip   
  ([^ZipOutputStream zos ^String path] (add-to-zip zos path path))
  ([^ZipOutputStream zos ^String path ^String as-path]
   (.putNextEntry zos (entry-from-path as-path))
   (let [content (.getBytes (slurp path))] ;;;; this is a BUG, just here temporarily!
     (.write zos  content 0 (count content)))
   (.closeEntry zos)
   zos))

(defn- convert-files-arguments [xo]
  (reduce (fn [acc v] 
            (if (vector? v) 
              (do 
                (assert (= 2 (count v)))
                (conj acc (vec v)))
              (conj acc [v v])
              )) 
          [] 
          xo))

;; TODO: the first idea was to use arguments in this form:
;; file1 file2 :as foo file4 file5 :as bar
;; but I dropped because it was ehm… too difficult :p
(defn zip-files [^String destination & files]
  "Zip a list of files, with files specified in this way:

file1 file2 [file3  foo] file4 [file5  bar]

and returns the destination path or nil in case of error."
  (try 
    (with-open [zos (open-zip! destination)]
      (dorun 
       (map (fn [[s d]] (add-to-zip zos s d))
              (convert-files-arguments files))))
    destination
    (catch java.io.IOException e 
      (do 
        (println "Error while zipping:" e)  ;; TODO/FIXME: fix this
        nil))))

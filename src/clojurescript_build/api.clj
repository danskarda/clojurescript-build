(ns clojurescript-build.api
  (:require
   [cljs.analyzer]
   [cljs.env :as env]
   [cljs.closure]
   [clojure.string :as string]
   [clojure.java.io :as io]   
   [clojure.set :refer [intersection]]))

;; The use of the the term api here is to indicate potential a
;; potential stabe api for clojurescript compiler consumers

;; cljs.clojure/output-directory
(defn output-directory [opts]
  (or (:output-dir opts) "out"))

;; it would be great to have this be stable in the compiler
#_(defn cljs-target-file-from-ns [output-dir ns-sym]
    (util/to-target-file (output-directory { :output-dir output-dir })
                         {:ns ns-sym }))

;; this is independant of compiler changes
(defn cljs-target-file-from-ns [output-dir ns-sym]
  (let [relative-path (string/split
                       (clojure.lang.Compiler/munge (str ns-sym))
                       #"\.")
        parents       (butlast relative-path)
        path          (apply str (interpose java.io.File/separator
                                            (cons output-dir parents)))]
    (io/file (io/file path) 
             (str (last relative-path) ".js"))))

(defn touch-target-file-for-ns!
  "Backdates a cljs target file so that it the cljs compiler will recompile it."
  [output-dir ns-sym]
  (let [s (cljs-target-file-from-ns output-dir ns-sym)]
    (when (.exists s)
      (.setLastModified s 5000))))

;; potentially a better api name
(def mark-ns-for-recompile! touch-target-file-for-ns!)

;; POTENTIAL API call
(defn macro-dependants-for-namespaces
  "Takes a list of namespaces of clj sources that define macros and
   returns a list cljs ns symbols that depend on those macro
   namespaces."
  [namespaces]
  (map :name
       (let [namespaces-set (set namespaces)]
         (filter (fn [x] (not-empty
                         (intersection namespaces-set (-> x :require-macros vals set))))
                 (vals (:cljs.analyzer/namespaces @env/*compiler*))))))

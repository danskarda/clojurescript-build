(ns clojurescript-build.api
  (:require
   [cljs.util :as util]
   [cljs.analyzer]
   [cljs.env :as env]
   [cljs.closure]
   [clojure.set :refer [intersection]]))

(defn cljs-target-file-from-ns [output-dir ns-sym]
  (util/to-target-file (cljs.closure/output-directory { :output-dir output-dir })
                       {:ns ns-sym }))

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

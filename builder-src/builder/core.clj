(ns builder.core
  (:require
   [clojure.pprint :as p]
   [cljs.env :as env]
   [cljs.util :as util]
   [cljs.closure :as cl]
   [cljs.analyzer :as ana]
   [cljs.compiler]
   [cljs.closure :as cljsc]
   [clojure.set :refer [intersection]]
   [clojure.java.io :refer [as-file file]]))

(def e (env/default-compiler-env {}))

;; from cljsbuild
(defrecord SourcePaths [paths]
  cljs.closure/Compilable
  (-compile [_ opts]
    (mapcat #(cl/-compile % opts) paths)))

;; from cljsbuild
(defn drop-extension [path]
  (let [i (.lastIndexOf path ".")]
    (if (pos? i)
      (subs path 0 i)
      path)))

;; from cljsbuild
(defn relativize [file-resource]
  (let [path (.getCanonicalPath (:source-file file-resource))
        parent (.getCanonicalPath (:source-dir file-resource))]
    (if (.startsWith path parent)
      (subs path (count parent))
      path)))

(defn reload-lib [file-resource]
  ;; XXX try catch needed here
  (load (drop-extension (relativize file-resource))))


(defn files-like* [ends-with dir]
  (map (fn [f] {:source-dir (file dir)
               :source-file f })
       (filter #(let [name (.getName ^java.io.File %)]
             (and (.endsWith name ends-with)
                  (not= \. (first name))))
               (file-seq (file dir)))))

(defn files-like [ends-with dirs]
  (mapcat (partial files-like* ends-with) dirs))

(defn clj-files-in-dirs [dirs]
  (files-like ".clj" dirs))

(defn macro-file?
  "Super innacurate but the cost of being wrong is minimal."
  [f] (.contains (slurp (:source-file f)) "(defmacro"))

(defn annotate-macro-file [f]
  (assoc f :macro-file? (macro-file? f)))

;; api call
(defn get-macro-dependants-from-namespaces
  "Given a set of macro namespaces return a bunch of a"
  [nmspaces]
  (map (fn [{:keys [name require-macros]}] {:name name :require-macros (set (vals require-macros))})
       (filter (fn [x] (not-empty (intersection nmspaces (-> x :require-macros vals set))))
               (vals (:cljs.analyzer/namespaces @e)))))

;; this is only for clj files
;; this needs to be fixed 
(defn get-clj-ns [x] (-> x :source-file ana/parse-ns :ns))

(defn get-clj-namespaces [file-resources]
  (set (map get-clj-ns file-resources)))

;; this gets cljs dependant ns for macro files
(defn get-macro-dependants [env-atom macro-file-resources]
  (let [namespaces (get-clj-namespaces macro-file-resources)]
    (set (map :name (get-macro-dependants-from-namespaces env-atom namespaces)))))

;; this makes me think we can do a double dispatch api based around
;; namespaces
;; API call
(defn compile-data-from-ns [env-atom ns-sym]
  (second
   (first
    (filter (fn [[k v]]
              (contains? (set (:provides v)) (cljs.compiler/munge (name ns-sym))))
            (:cljs.closure/compiled-cljs @env-atom)))))

;; api function
(defn get-source-file-from-ns [env-atom ns-sym]
  ;; should probably use ISourceMap for this
  (file (:source-url (compile-data-from-ns env-atom ns-sym))))

(defn touch-source-file-for-ns! [env-atom ns-sym]
  (let [s (get-source-file-from-ns env-atom ns-sym)]
    (.setLastModified s (System/currentTimeMillis))))

;; this could change based on the compiler implementation
(def mark-for-recompile! touch-source-file-for-ns!)

(defn mark-known-dependants-for-recompile! [env-atom file-resources]
  (doseq [ns-sym (get-macro-dependants env-atom file-resources)]
    (mark-for-recompile! env-atom ns-sym)))

(defn mtime [f] (if (.exists f) (.lastModified f) 0))

(defn last-compile-time [{:keys [output-to] :as opts}] (mtime (file output-to)))

;; so there is the case where known macro files have changed but on
;; disk a cljs source file newly requires a macro file which has also
;; changed, how can I reliably tell if this macro file has changed?
;; Seems better to compare the macro file against its previously
;; recorded mtime, this is much faster, but perhaps can race?

(defn get-changed-files [file-resources since-time]
  (filter (fn [x] (> (.lastModified (:source-file x)) since-time))
          file-resources))

(defn group-clj-macro-files [file-resources]
  (let [clj-file-map (group-by :macro-file?
                               (map annotate-macro-file file-resources))]
    {:macro-files     (clj-file-map true)
     :non-macro-files (clj-file-map false) }))

;; relading helpers

(defn macro-files-to-reload [src-dirs since-time]
  ;; if a clj file has changed
  ;; return all macro files
  (let [clj-files          (map annotate-macro-file (clj-files-in-dirs src-dirs))
        changed-clj-files  (get-changed-files clj-files since-time)]
    (filter :macro-file?
            (if (first (filter #(not (:macro-file? %)) changed-clj-files))
              clj-files
              changed-clj-files))))

;; the only option that we are counting on here is :output-to
;; need to ensure that we can handle a blank :output-to
(defn build-multiple
  "A cljs builder that handles incremental .clj file changes."
  ([src-dirs opts]
     (build-multiple src-dirs opts
                     (if-not (nil? env/*compiler*)
                       env/*compiler*
                       (env/default-compiler-env opts))))
  ([src-dirs opts compiler-env]
     (env/with-compiler-env compiler-env
       (let [started-at          (System/currentTimeMillis)
             changed-macro-files (macro-files-to-reload src-dirs (last-compile-time opts))]
         (when (not-empty changed-macro-files)
           (doseq [macro-file changed-macro-files]
             (reload-lib macro-file))
           (mark-known-dependants-for-recompile! e changed-macro-files))

         (cljsc/build (SourcePaths. src-dirs) opts compiler-env)
         (.setLastModified (file (:output-to opts)) started-at)))))

;; from here down is only for dev

(comment



  (clj-files-in-dirs ["src"])
  (get-changed-files (clj-files-in-dirs ["src"]) (last-compile-time {:output-to "outer/checkbuild.js"}))
  (map annotate-macro-file (clj-files-in-dirs ["src"]))
  (group-by :macro-file? (map annotate-macro-file (clj-files-in-dirs ["src"])))
  (group-clj-macro-files   (clj-files-in-dirs ["src"]))
  (macro-files-to-reload ["src"] (last-compile-time {:output-to "outer/checkbuild.js"}))

  (get-dependants
   (macro-files-to-reload ["src"] (last-compile-time {:output-to "outer/checkbuild.js"})))

  (build* ["src/checkbuild/core.cljs"] {} (env/default-compiler-env))
  
  
  (build ["src"])
  )

(def options { :output-to "outer/checkbuild.js"
               :output-dir "outer/out"
               :optimizations :none
               :source-map true
               :warnings true
               #_:output-wrapper #_false ;; I don't know what this is
              })

(defn build* [src-dirs opts e]
  (cljsc/build (SourcePaths. src-dirs) opts e))

(defn build [src-dirs]
  (build* src-dirs options e))

(comment this is the env structure
         {
          :cljs.analyzer/namespaces

          { 'checkbuild.core
           {:imports nil,
            :require-macros nil,
            :use-macros nil,
            :requires {fw figwheel.client,
                       figwheel.client figwheel.client,
                       om.core om.core,
                       sablono.core sablono.core},
            :uses nil,
            :excludes #{},
            :doc nil,
            :name checkbuild.core}
           }

          :cljs.closure/compiled-js
          { "/Users/brucehauman/workspace/checkbuild/outer/out/figwheel/client.js"

            {:foreign nil,
             :url URLFile
             :source-url URLFile
             :provides ("figwheel.client"),
             :requires ("goog.Uri" "cljs.core" "cljs.core.async" "figwheel.client.file-reloading" "figwheel.client.heads-up" "figwheel.client.socket"),
             :lines 1369,
             :source-map nil}}
          :cljs.analyzer/analized-cljs 
          {"file:/Users/brucehauman/.m2/repository/org/clojure/core.async/0.1.346.0-17112a-alpha/core.async-0.1.346.0-17112a-alpha.jar!/cljs/core/async/impl/buffers.cljs" true}
          
          }

         )

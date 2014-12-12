(ns clojurescript-build.core
  (:require
   [clojure.pprint :as p]
   [cljs.env :as env]
   [cljs.util :as util]
   [cljs.closure :as cl]
   [cljs.analyzer :as ana]
   [cljs.compiler]
   [cljs.closure :as cljsc]
   [clojure.set :refer [intersection]]
   [clojure.java.io :refer [file] :as io]))

;; debug
(defn l [x]
  (p/pprint x)
  x)

;; tried to move the stuff that needs another look to the top of the file

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

;; should be able to reuse something here with out including tons of deps
(defn ns-from-file [f]
  (try
    (when (.exists f)
      (with-open [rdr (io/reader f)]
        (-> (java.io.PushbackReader. rdr)
            read
            second)))
    ;; better exception here eh?
    (catch java.lang.RuntimeException e
      nil)))

#_(defn cljs-target-file [opts cljs-file]
  (util/to-target-file (cljs.closure/output-directory opts)
                       (ana/parse-ns cljs-file)))

;; how fast is file-seq?
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
  "Super innacurate but the cost of being wrong here is minimal."
  [f] (.contains (slurp (:source-file f)) "(defmacro"))

(defn annotate-macro-file [f]
  (assoc f :macro-file? (macro-file? f)))

;; POTENTIAL API call
(defn macro-dependants-for-namespaces
  "Given a list of clj macro namespace symbols return set of dependant cljs ns symbols."
  [namespaces]
  (map :name
       (let [namespaces-set (set namespaces)]
         (filter (fn [x] (not-empty (intersection namespaces-set (-> x :require-macros vals set))))
                 (vals (:cljs.analyzer/namespaces @env/*compiler*))))))

;; POTENTIAL API call
;; we can reshape the result and augment it with a :name key
;; to standardize the api
(defn compile-data-for-ns
  "Given a namspace symbol return a map of compile data for that namespace."
  [ns-sym]
  (second
   (first
    (filter (fn [[k v]]
              (contains? (set (:provides v)) (cljs.compiler/munge (name ns-sym))))
            (:cljs.closure/compiled-cljs @env/*compiler*)))))

;; this is only for clj files
;; this needs to be fixed 
(defn get-clj-ns [x] (-> x :source-file ns-from-file))

(defn get-clj-namespaces [file-resources]
  (map get-clj-ns file-resources))

;; this gets cljs dependant ns for macro files
(defn macro-dependants [macro-file-resources]
  (let [namespaces (get-clj-namespaces macro-file-resources)]
    (macro-dependants-for-namespaces namespaces)))

(defn get-source-file-from-ns [ns-sym]
  ;; should probably use ISourceMap for this
  (file (:source-url (compile-data-for-ns ns-sym))))

(defn touch-source-file-for-ns! [ns-sym]
  (let [s (get-source-file-from-ns ns-sym)]
    (.setLastModified s (System/currentTimeMillis))))

(defn mark-known-dependants-for-recompile! [file-resources]
  (doseq [ns-sym (macro-dependants file-resources)]
    (touch-source-file-for-ns! ns-sym)))

;; tracking compile times
(defn compiled-at-marker [opts]
  ;; there is an oportunity here to make this a unique marker
  (file (cljs.closure/output-directory opts) ".cljs-last-compiled-at"))

(defn last-compile-time [opts]
  (.lastModified (compiled-at-marker opts)))

(defn touch-or-create-file [f timest]
  (when-not (.exists f)
    (.mkdirs f)
    (.createNewFile f))
  (.setLastModified f timest))

(defn get-changed-files [file-resources since-time]
  (filter (fn [x] (> (.lastModified (:source-file x)) since-time))
          file-resources))

(defn group-clj-macro-files [file-resources]
  (let [clj-file-map (group-by :macro-file?
                               (map annotate-macro-file file-resources))]
    {:macro-files     (clj-file-map true)
     :non-macro-files (clj-file-map false) }))

(defn macro-files-to-reload [src-dirs since-time]
  ;; if a clj file has changed
  ;; return all macro files
  (let [clj-files          (map annotate-macro-file (clj-files-in-dirs src-dirs))
        changed-clj-files  (get-changed-files clj-files since-time)]
    (filter :macro-file?
            (if (first (filter #(not (:macro-file? %)) changed-clj-files))
              clj-files
              changed-clj-files))))

(defn build-multiple-root
  "Builds ClojureScript source directories incrementally. It is
   sensitive to changes in .clj in your .cljs source directories files.

   .cljs files that are dependent on changed .clj files will be marked
   for recompilation.
 
   This build function provides a very fast compile time if you are modifying .clj
   files.

   This build is wrapper around cljs.closure/build and as such it
   takes all the options that cljsc/build takes. It does not alter any
   of the options you are sending to build.

   The only difference from build is that build-multiple-root takes a list of
   source directories as its first argument."
  ([src-dirs opts]
     (build-multiple-root src-dirs opts
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
           (mark-known-dependants-for-recompile! changed-macro-files))

         (cljsc/build (SourcePaths. src-dirs) opts compiler-env)
         (touch-or-create-file (compiled-at-marker opts) started-at)))))

(defn js-files-that-can-change-build [opts]
  (->> (or (:libs opts) [])
       (files-like ".js")
       (remove #(.startsWith (.getPath (:source-file %))
                             (cljs.closure/output-directory opts)))
       (remove #(and
                 (:output-to opts)
                 (.endsWith (.getPath (:source-file %))
                            (:output-to opts))))))

(defn files-that-can-change-build [src-dirs opts]
  ;; only .cljs, .clj, and :libs files can change build
  (let [cljs-files (files-like ".cljs" src-dirs)
        clj-files  (files-like ".clj" src-dirs)
        js-files   (if (:libs opts)
                     (js-files-that-can-change-build opts)
                     [])]
    (concat cljs-files clj-files js-files)))





;; from here down is only for dev

(def e (env/default-compiler-env options))

(comment

  (js-files-that-can-change-build (assoc options :libs ["outer/out"]))
  
  (clj-files-in-dirs ["src"])
  (get-changed-files (clj-files-in-dirs ["src"]) (last-compile-time {:output-to "outer/checkbuild.js"}))
  (touch-or-create-file (compiled-at-marker {}) (System/currentTimeMillis))
  (map annotate-macro-file (clj-files-in-dirs ["src"]))
  (group-clj-macro-files   (clj-files-in-dirs ["src"]))
  (macro-files-to-reload ["src"] (last-compile-time {:output-to "outer/checkbuild.js"}))

  (env/with-compiler-env e
    (macro-dependants-for-namespaces ['checkbuild.macros-again]))

  (env/with-compiler-env e
    (macro-dependants-for-namespaces ['checkbuild.macros]))
  
  (env/with-compiler-env e
    (macro-dependants
     (macro-files-to-reload ["src"] (last-compile-time {:output-to "outer/checkbuild.js"}))))

  (env/with-compiler-env e 
    (macro-dependants (:macro-files (group-clj-macro-files (clj-files-in-dirs ["src"])))))

  (build* ["src"] {} (env/default-compiler-env))
  
  
  (build ["resources/src"])
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

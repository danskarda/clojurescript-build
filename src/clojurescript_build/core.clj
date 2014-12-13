(ns clojurescript-build.core
  (:require
   [clojure.pprint :as p]
   [cljs.env :as env]
   [cljs.closure]
   [clojure.java.io :refer [file] :as io]
   [clojurescript-build.api :as api]))

;; debug helper
(defn l [x] (p/pprint x) x)

;; from cljsbuild
(defrecord CompilableSourcePaths [paths]
  cljs.closure/Compilable
  (-compile [_ opts]
    (mapcat #(cljs.closure/-compile % opts) paths)))

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

; Super innacurate but the cost of being wrong here is practically none
(defn macro-file?
  [f] (.contains (slurp (:source-file f)) "(defmacro"))

(defn annotate-macro-file [f]
  (assoc f :macro-file? (macro-file? f)))

;; this is only for clj files
;; this needs to be fixed 
(defn get-clj-ns [x] (-> x :source-file ns-from-file))

(defn get-clj-namespaces [file-resources]
  (map get-clj-ns file-resources))

;; this gets cljs dependant ns for macro files
(defn macro-dependants [macro-file-resources]
  (let [namespaces (get-clj-namespaces macro-file-resources)]
    (api/macro-dependants-for-namespaces namespaces)))

(defn mark-known-dependants-for-recompile! [opts file-resources]
  (doseq [ns-sym (macro-dependants file-resources)]
    (api/mark-ns-for-recompile! (:output-dir opts) ns-sym)))

;; tracking compile times
(defn compiled-at-marker [opts]
  ;; there is an oportunity here to make this a unique marker
  (file (api/output-directory opts) ".cljs-last-compiled-at"))

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

(defn relevant-macro-files [clj-files changed-clj-files]
  (let [non-macro-clj? (first (filter #(not (:macro-file? %)) changed-clj-files))]
    (filter :macro-file?
            (if non-macro-clj? clj-files changed-clj-files))))

(defn handle-source-reloading*
  [src-dirs opts last-compile-time']
  (let [clj-files          (map annotate-macro-file (clj-files-in-dirs src-dirs))
        changed-clj-files  (get-changed-files clj-files last-compile-time')]
    (when (not-empty changed-clj-files)
      ;; reload all changed files
      (doseq [clj-file changed-clj-files] (reload-lib clj-file))
      ;; mark affected cljs files for recompile
      (let [rel-files (relevant-macro-files clj-files changed-clj-files)]
        (mark-known-dependants-for-recompile! opts rel-files)
        rel-files))))

(defn handle-source-reloading [src-dirs opts]
   (handle-source-reloading* src-dirs opts (last-compile-time opts)))

(defn build-source-paths
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
     (build-source-paths src-dirs opts
                         (if-not (nil? env/*compiler*)
                           env/*compiler*
                           (env/default-compiler-env opts))))
  ([src-dirs opts compiler-env]
     ;; TODO should probably ensure that src-dirs is a list of
     ;; directories as this is the expectation
     ;; or only do clj dependancy checking for directories
     (env/with-compiler-env compiler-env
       (let [started-at          (System/currentTimeMillis)]
         (handle-source-reloading src-dirs opts)
         (cljs.closure/build (CompilableSourcePaths. src-dirs) opts compiler-env)
         (touch-or-create-file (compiled-at-marker opts) started-at)))))

(comment
  (def options { :output-to "outer/checkbuild.js"
                 :output-dir "outer/out"
                 :optimizations :none
                 ;; :source-map true
                :warnings true })
  
  (def e (env/default-compiler-env options))

  (defn t [f]
    (.setLastModified (io/file f) (System/currentTimeMillis)))

  ;; forces onery, helper and core to compile
  (t "test/src/checkbuild/macros.clj") 

  ;; forces only helper to compile
  (t "test/src/checkbuild/macros_again.clj")
  
  ;; forces onery, helper and core to compile
  (t "test/src/checkbuild/mhelp.clj")

  ;; no_macros should not be recompiled

  (build-source-paths ["test/src"] options e)

  
  )

(defn js-files-that-can-change-build [opts]
  (->> (or (:libs opts) [])
       (files-like ".js")
       (remove #(.startsWith (.getPath (:source-file %))
                             (api/output-directory opts)))
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

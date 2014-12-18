(ns clojurescript-build.auto
  (:require
   [clojurescript-build.core :refer [build-source-paths files-that-can-change-build]]
   [clojure.stacktrace :as stack]))

;; from cljsbuild
(defn get-dependency-mtimes [src-dirs build-options]
  (let [files (files-that-can-change-build src-dirs build-options)]
    (into {}
          (map (juxt (fn [f] (.getCanonicalPath f))
               (fn [f] (.lastModified f)))
               (map :source-file files)))))

(def reset-color "\u001b[0m")
(def foreground-red "\u001b[31m")
(def foreground-green "\u001b[32m")

(defn- colorizer [c]
  (fn [& args]
    (str c (apply str args) reset-color)))

(def red (colorizer foreground-red))
(def green (colorizer foreground-green))

;; from cljsbuild
(defn- elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

(defn compile-start [src-dirs build-options]
  (println (str reset-color "Compiling \""
                (:output-to build-options) "\" from " (pr-str src-dirs) "..."))
  (flush))

(defn compile-success [src-dirs build-options started-at]
  (println (green (str "Successfully compiled \""
                       (:output-to build-options) "\" in " (elapsed started-at) ".")))
  (flush))

(defn compile-fail [src-dirs build-options e]
  (println (red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (stack/print-cause-trace e 1)
  (println reset-color)
  (flush))

(defn autobuild
  "Autobuild ClojureScript sources.
   
   (autobuild [\"test/src\"] { :output-to \"outer/checkbuild.js\"
                               :output-dir \"outer/out\"
                               :optimizations :none
                               ;; :source-map true
                               :warnings true })"
  ([src-dirs build-options]
   (autobuild src-dirs build-options {}))
  ([src-dirs build-options auto-build-options]
   (let [auto-options (merge {:on-compile-start   compile-start
                              :on-compile-success compile-success
                              :on-compile-fail    compile-fail }
                             auto-build-options)
         compiler-env (cljs.env/default-compiler-env build-options)]
     (loop [dependency-mtimes {}]
       (let [new-mtimes (get-dependency-mtimes src-dirs build-options)]
         (when (not= new-mtimes dependency-mtimes)
           (try
             (let [started-at (System/currentTimeMillis)]
               ((:on-compile-start auto-options) src-dirs build-options)
               (build-source-paths src-dirs build-options compiler-env)
               ((:on-compile-success auto-options) src-dirs build-options started-at))
            (catch Throwable e
              ((:on-compile-fail auto-options) src-dirs build-options e))))
         (Thread/sleep 100)
         (recur new-mtimes))))))

(comment
  (autobuild ["test/src"] { :output-to "outer/checkbuild.js"
                            :output-dir "outer/out"
                            :optimizations :none
                            ;; :source-map true
                            :warnings true })
)

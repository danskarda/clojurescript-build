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

(defn colorizer [c]
  (fn [& args]
    (str c (apply str args) reset-color)))

(def red (colorizer foreground-red))
(def green (colorizer foreground-green))

;; from cljsbuild
(defn elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

(defn compile-start [{:keys [build-options src-dirs]}]
  (println (str reset-color "Compiling \""
                (:output-to build-options) "\" from " (pr-str src-dirs) "..."))
  (flush))

(defn compile-success [{:keys [build-options started-at]}]
  (println (green (str "Successfully compiled \""
                       (:output-to build-options) "\" in " (elapsed started-at) ".")))
  (flush))

(defn compile-fail [{:keys [build-options exception]}]
  (println (red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (stack/print-cause-trace exception 1)
  (println reset-color)
  (flush))

(defn build-once [{:keys [src-dirs build-options compiler-env] :as state}]
  (let [started-at (System/currentTimeMillis)]
    (try
      (compile-start (assoc state :started-at started-at))
      (let [build-result (build-source-paths src-dirs build-options)]
        (compile-success (assoc state
                                :build-result build-result
                                :started-at   started-at)))
      (catch Throwable e
        (compile-fail (assoc state
                             :started-at started-at
                             :exception e))))))

(defn autobuild*
  [{:keys [src-dirs build-options builder each-iteration-hook] :as opts}]
  (let [builder' (or builder build-once)
        ;; persist compile-env across builds
        compiler-env (or cljs.env/*compiler* (cljs.env/default-compiler-env build-options))]
     (loop [dependency-mtimes {}]
       (let [new-mtimes (get-dependency-mtimes src-dirs build-options)
             cur-state (assoc opts
                              :compiler-env compiler-env
                              :old-mtimes dependency-mtimes
                              :new-mtimes new-mtimes)]
         (when (not= new-mtimes dependency-mtimes)
           (builder' cur-state))
         (when each-iteration-hook
           (each-iteration-hook cur-state))
         (Thread/sleep 100)
         (recur new-mtimes)))))

(defn autobuild
  "Autobuild ClojureScript sources.
   (autobuild [\"test/src\"] { :output-to \"outer/checkbuild.js\"
                               :output-dir \"outer/out\"
                               :optimizations :none
                               ;; :source-map true
                               :warnings true })

  The third arguement is a builder function that has the same
  signature as the build-once function. This allows you to wrap and do
  what ever house keeping you need to take care of around the
  build-source-paths function. For an example builder function see the
  build-once function above as it is the default bulder function."
  ([src-dirs build-options]
   (autobuild src-dirs build-options build-once))
  ([src-dirs build-options builder]
   (autobuild* {:src-dirs      src-dirs
                :build-options build-options
                :builder       builder})))

(comment
  (autobuild ["test/src"] { :output-to "outer/checkbuild.js"
                            :output-dir "outer/out"
                            :optimizations :none
                            ;; :source-map true
                            :warnings true })

  (def compiler (future
                  (autobuild ["test/src"] { :output-to "outer/checkbuild.js"
                                           :output-dir "outer/out"
                                           :optimizations :none
                                           ;; :source-map true
                                           :warnings true })))
  
  (future-cancel compiler)

  )

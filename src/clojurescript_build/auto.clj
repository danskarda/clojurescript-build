(ns clojurescript-build.auto
  (:require
   [clojurescript-build.core :refer [build-source-paths* files-that-can-change-build]]
   [clojure.core.async :refer [go-loop timeout chan alts! close!]]
   [clojure.stacktrace :as stack]
   [cljs.analyzer]))

;; from cljsbuild
(defn get-dependency-mtimes [source-paths build-options]
  (let [files (files-that-can-change-build source-paths build-options)]
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

(defn compile-start [{:keys [build-options source-paths] :as build}]
  (println (str reset-color "Compiling \""
                (:output-to build-options) "\" from " (pr-str source-paths) "..."))
  (flush)
  build)

(defn compile-success [{:keys [build-options started-at] :as build}]
  (println (green (str "Successfully compiled \""
                       (:output-to build-options) "\" in " (elapsed started-at) ".")))
  (flush)
  build)

(defn compile-fail [{:keys [build-options exception] :as build}]
  (println (red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (stack/print-cause-trace exception 1)
  (println reset-color)
  (flush)
  build)

(defn time-build [builder]
  (fn [build]
    (let [started-at (System/currentTimeMillis)]
      (builder (assoc build :started-at started-at)))))

(defn before [builder callback]
  (fn [build]
    (callback build)
    (builder build)))

(defn after [builder callback]
  (fn [build]
    (let [res (builder build)]
      (callback res)
      res)))

(defn error [builder callback]
  (fn [build]
    (try
      (builder build)
      (catch Throwable e
        (callback (assoc build :exception e))))))

(defn warning-message-handler [callback]
  (fn [warning-type env extra]
    (when (warning-type cljs.analyzer/*cljs-warnings*)
      (when-let [s (cljs.analyzer/error-message warning-type extra)]
        (callback (cljs.analyzer/message env s))))))

(defn warning [builder warn-handler]
  (let [warning-handlers (conj cljs.analyzer/*cljs-warning-handlers*
                               warn-handler)]
    (fn [build]
      (binding [cljs.analyzer/*cljs-warning-handlers* warning-handlers]
        (builder build)))))

(def build-once 
  (-> build-source-paths*
    time-build
    (before  compile-start)
    (after   compile-success)
    (error   compile-fail)))

(defn make-conditional-builder [builder]
  (fn [{:keys [source-paths
              build-options
              compiler-env
              dependency-mtimes] :as build}]
    (let [new-mtimes (get-dependency-mtimes source-paths build-options)]
      (when (not= new-mtimes dependency-mtimes)
        (builder (assoc build
                        :old-mtimes dependency-mtimes
                        :new-mtimes new-mtimes)))
      (assoc build :dependency-mtimes new-mtimes))))

(defn prep-build [build]
  (assoc build
         ;; add support for cljsbuild :compiler option
         ;; I think :build-options is a better name
         :build-options (or (:build-options build)
                            (:compiler build))
         :compiler-env (or (:compiler-env build)
                           (cljs.env/default-compiler-env
                             (:build-options build)))
         :dependency-mtimes {}))

(defn stop-autobuild! [{:keys [break-loop-ch] :as autobuild-struct}]
  (if break-loop-ch
    (do
      (close! break-loop-ch)
      (dissoc autobuild-struct :break-loop-ch))
    autobuild-struct))

(defn autobuild*
  "Autobuild ClojureScript Source files.
  Takes a map with the following keys:

  :builds 
  Is a required vector of builds to watch and build. For example:
    [{:source-paths [\"src\" \"dev/src\"]
      :build-options {:output-to \"resources/public/out/example.js\"
                      :output-dir \"resources/public/out\"
                      :optimizations :none}}
     {:source-paths [\"src\"]
      :build-options {:output-to \"resources/public/out/example.js\"
                      :optimizations :simple}}]

  :builder 
  An optional builder which wraps clojurescript-build.core/build-source-paths.
  See the default builder clojurescript-build.auto/build-once as an example. 
  
  :each-iteration-hook
  An optional function which gets executed every iteration of the watch loop.

  :wait-time 
  An integer which is the number of milliseconds the autobuild loop
  pauses between iterations.

  Returns the options map that was provided with the addition of
  a :break-loop-ch key which holds a core.async channel which when
  provided a value will stop the autobuild loop. 

  If you store the result of this call you can call stop-autobuild! on
  it to terminate the autobuild loop.

  You can then pass this map back to autobuild to restart the watching process.

  Helpful usage pattern:

  (def autobuild-data (atom {:builds 
                              [{:source-paths [\"src\" \"dev/src\"]
                                :build-options {:output-to \"out/example.js\"
                                               :output-dir \"out\"
                                               :optimizations :none}}]})
  Start building:
  (swap! autobuild-data autobuild*)

  Stop building
  (swap! autobuild-data stop-autobuild!)"
  [{:keys [builds builder each-iteration-hook wait-time] :as opts}]
  (let [wait-time          (or wait-time 100)
        conditional-build! (make-conditional-builder (or builder build-once))
        break-loop-ch      (chan)]
    (go-loop [builds (mapv prep-build builds)]
      (let [[v ch] (alts! [(timeout wait-time) break-loop-ch])]
        (when (not= ch break-loop-ch)
          (when each-iteration-hook (each-iteration-hook opts))
          (recur (mapv conditional-build! builds)))))
    (assoc opts :break-loop-ch break-loop-ch)))

#_(defn autobuild-blocking*
  "Same as autobuild but blocks while watching."
  [{:keys [builds builder each-iteration-hook wait-time] :as opts}]
  (let [wait-time          (or wait-time 100)
        conditional-build! (make-conditional-builder (or builder build-once))]
    (loop [builds (mapv prep-build builds)]
      (Thread/sleep wait-time)
      (when each-iteration-hook (each-iteration-hook opts))
      (recur (mapv conditional-build! builds)))))

(defn autobuild
  "Autobuild ClojureScript sources.
   (autobuild [\"test/src\"] { :output-to \"outer/checkbuild.js\"
                               :output-dir \"outer/out\"
                               :optimizations :none
                               ;; :source-map true
                               :warnings true })

  The third parameter is a builder function that has the same
  signature as the build-once function. This allows you to wrap and do
  what ever house keeping you need to take care of around the
  build-source-paths function. For an example builder function see the
  build-once function above as it is the default bulder function."
  ([source-paths build-options]
   (autobuild source-paths build-options build-once))
  ([source-paths build-options builder]
   (autobuild* {:builds [{:source-paths      source-paths
                          :build-options build-options}]
                :builder       builder})))

(comment
  (autobuild ["test/src"] { :output-to "outer/checkbuild.js"
                            :output-dir "outer/out"
                            :optimizations :none
                            ;; :source-map true
                           :warnings true })

  (def auto (autobuild* {:builds [{:source-paths ["test/src"]
                                   :build-options { :output-to "outer/checkbuild.js"
                                                   :output-dir "outer/out"
                                                   :optimizations :none
                                                   ;; :source-map true
                                                   :warnings true }}
                                  {:source-paths ["test/src"]
                                   :build-options {:output-to "outer/checkbuild-simple.js"
                                                   :optimizations :simple }}]})) 
  
  (stop-autobuild! auto)

  )

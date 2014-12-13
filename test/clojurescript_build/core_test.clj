(ns clojurescript-build.core-test
  (:require
   [clojurescript-build.core :as b]
   [clojurescript-build.api :as api]   
   [cljs.env :as env]
   [cljs.closure :as cljsc]
   [clojure.java.io :as io]
   [clojure.test :refer [testing is deftest]]))

(defn l [x] (print x) x)

(def options { :output-to "outer/checkbuild.js"
               :output-dir "outer/out"
               :optimizations :none
               :source-map true
               :warnings true })

(defonce e (env/default-compiler-env options))

(def test-time (System/currentTimeMillis))

(def clj-files (b/clj-files-in-dirs ["test/src"]))

(def clj-files-name-set
  #{"test/src/checkbuild/macros.clj"
    "test/src/checkbuild/macros_again.clj"
    "test/src/checkbuild/mhelp.clj"})

(defn get-file [nm]
  (first (b/files-like nm [ "test/src"])))

(defn touch [path t]
  (.setLastModified (io/file path) (+ test-time 1000)))

(defn source-file-set [file-resources]
  (set (map #(.getPath (:source-file %))
            file-resources)))

(defonce build-once (b/build-source-paths ["test/src"] options e))

(deftest clj-files-in-dirs-test
  (let [frs  (b/clj-files-in-dirs ["test/src"])]
    (is (= (set (map #(.getPath (:source-dir %)) frs))
           #{"test/src"}))
    (is (= (source-file-set frs)
           clj-files-name-set))))

(deftest get-changed-files-test
    ;; testing touch or create file here
    (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
    (is (= []
           (b/get-changed-files clj-files (+ test-time 1000))))
    (is (= (set (source-file-set
                 (b/get-changed-files clj-files (- test-time 1000))))
           clj-files-name-set)))

(deftest annotate-macro-file-test
  (is (not (:macro-file? (b/annotate-macro-file (get-file "mhelp.clj")))))
  (is (:macro-file? (b/annotate-macro-file (get-file "macros.clj"))))
  (is (:macro-file? (b/annotate-macro-file (get-file "macros_again.clj")))))

;; very very side effecty
;; I should overide get-changed-files here

(deftest handle-source-reloading-test []
  (env/with-compiler-env e
    (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)

    (is (= #{"test/src/checkbuild/macros.clj"
             "test/src/checkbuild/macros_again.clj"}
           (source-file-set (b/handle-source-reloading* ["test/src"]
                                                        options
                                                        (- test-time 1000)))))

    (is (empty? 
         (b/handle-source-reloading* ["resources/src"]
                                     options
                                     (+ test-time 1000))))
    
    (touch "test/src/checkbuild/mhelp.clj" (+ test-time 1000))

    (is (= #{"test/src/checkbuild/macros.clj"
             "test/src/checkbuild/macros_again.clj"}
           (source-file-set (b/handle-source-reloading* ["test/src"]
                                                        options
                                                        test-time))))


    (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
    
    (.setLastModified (io/file "test/src/checkbuild/macros.clj")
                      (+ test-time 1000))
    
    (is (= #{"test/src/checkbuild/macros.clj"}
           (source-file-set (b/handle-source-reloading* ["test/src"]
                                                        options
                                                        test-time))))

    (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
    
    (.setLastModified (io/file "test/src/checkbuild/macros_again.clj") (+ test-time 1000))
    
    (is (= #{"test/src/checkbuild/macros_again.clj"}
           (source-file-set (b/handle-source-reloading* ["test/src"]
                                                        options
                                                     test-time))))
    (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)))


(deftest test-macro-dependants
  (env/with-compiler-env e
    ;; only one file uses 
    (is (= ['checkbuild.helper]
           (api/macro-dependants-for-namespaces ['checkbuild.macros-again])))
    (is (= #{'checkbuild.onery 'checkbuild.helper 'checkbuild.core}
           (set (api/macro-dependants-for-namespaces ['checkbuild.macros]))))
    (is (= []
           (b/macro-dependants [(get-file "mhelp.clj")])))
    (is (= ['checkbuild.helper]
           (b/macro-dependants [(get-file "macros_again.clj")])))    
    (is (= #{'checkbuild.onery 'checkbuild.helper 'checkbuild.core}
           (set (b/macro-dependants [(get-file "macros.clj")]))))))

(deftest test-get-target-file []
  
  (env/with-compiler-env e
    (is (= (.getPath
            (api/cljs-target-file-from-ns
             (:output-dir options)
             'checkbuild.core))
           "outer/out/checkbuild/core.js"))

    (let [tfile (api/cljs-target-file-from-ns
                 (:output-dir options)
                 'checkbuild.core)]
      (.setLastModified tfile 58000)
      (Thread/sleep 10)
      (is (= 58000
             (.lastModified tfile)))
      (api/touch-target-file-for-ns!
       (:output-dir options)
       'checkbuild.core)
      (Thread/sleep 10)
      (is (= 5000
             (.lastModified tfile))))))

(clojure.test/run-tests)


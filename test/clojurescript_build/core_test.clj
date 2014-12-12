(ns clojurescript-build.core-test
  (:require
   [clojurescript-build.core :as b]
   [cljs.env :as env]
   [cljs.closure :as cljsc]
   [clojure.java.io :as io]
   [clojure.test :refer [testing is deftest]]))

(def options { :output-to "outer/checkbuild.js"
               :output-dir "outer/out"
               :optimizations :none
               :source-map true
               :warnings true })

(defonce e (env/default-compiler-env options))

(def test-time (System/currentTimeMillis))

(def clj-files (b/clj-files-in-dirs ["resources/src"]))

(def clj-files-name-set
  #{"resources/src/checkbuild/macros.clj"
    "resources/src/checkbuild/macros_again.clj"
    "resources/src/checkbuild/mhelp.clj"})

(defn get-file [nm]
  (first (b/files-like nm [ "resources/src"])))

(defn touch [path t]
  (.setLastModified (io/file path) (+ test-time 1000)))

(defn source-file-set [file-resources]
  (set (map #(.getPath (:source-file %))
            file-resources)))

(defonce build-once (b/build-multiple-root ["resources/src"] options e))

(deftest clj-files-in-dirs-test
  (let [frs  (b/clj-files-in-dirs ["resources/src"])]
    (is (= (set (map #(.getPath (:source-dir %)) frs))
           #{"resources/src"}))
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

(deftest group-clj-macro-files-test
  (let [grp (b/group-clj-macro-files clj-files)
        macs    (:macro-files grp)
        non-macs (:non-macro-files grp)]
    (is (= (source-file-set macs)
           #{"resources/src/checkbuild/macros.clj"
             "resources/src/checkbuild/macros_again.clj"}))
    (is (= (source-file-set non-macs)
           #{"resources/src/checkbuild/mhelp.clj"}))))


(deftest macro-files-to-reload-test
  (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
  (is (= #{"resources/src/checkbuild/macros.clj"
           "resources/src/checkbuild/macros_again.clj"}
       (source-file-set (b/macro-files-to-reload ["resources/src"]
                                                 (- test-time 1000)))))
  (is (empty? 
       (b/macro-files-to-reload ["resources/src"]
                                (+ test-time 1000))))
  
  (touch "resources/src/checkbuild/mhelp.clj" (+ test-time 1000))
  
  (is (= #{"resources/src/checkbuild/macros.clj"
           "resources/src/checkbuild/macros_again.clj"}
       (source-file-set (b/macro-files-to-reload ["resources/src"]
                                                 test-time))))

  (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
  
  (.setLastModified (io/file "resources/src/checkbuild/macros.clj")
                    (+ test-time 1000))

  (is (= #{"resources/src/checkbuild/macros.clj"}
       (source-file-set (b/macro-files-to-reload ["resources/src"]
                                                 test-time))))

  (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files)
  
  (.setLastModified (io/file "resources/src/checkbuild/macros_again.clj") (+ test-time 1000))

  (is (= #{"resources/src/checkbuild/macros_again.clj"}
         (source-file-set (b/macro-files-to-reload ["resources/src"]
                                                   test-time))))
  (mapv #(b/touch-or-create-file (:source-file %) test-time) clj-files))

(deftest test-macro-dependants
  (env/with-compiler-env e
    ;; only one file uses 
    (is (= ['checkbuild.helper]
           (b/macro-dependants-for-namespaces ['checkbuild.macros-again])))
    (is (= #{'checkbuild.onery 'checkbuild.helper 'checkbuild.core}
           (set (b/macro-dependants-for-namespaces ['checkbuild.macros]))))
    (is (= []
           (b/macro-dependants [(get-file "mhelp.clj")])))
    (is (= ['checkbuild.helper]
           (b/macro-dependants [(get-file "macros_again.clj")])))    
    (is (= #{'checkbuild.onery 'checkbuild.helper 'checkbuild.core}
           (set (b/macro-dependants [(get-file "macros.clj")]))))))



(clojure.test/run-tests)

;; we should clean up output files
;; 

(comment

  (js-files-that-can-change-build (assoc options :libs ["outer/out"]))
  

  (env/with-compiler-env e
    (b/macro-dependants-for-namespaces ['checkbuild.macros-again]))

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




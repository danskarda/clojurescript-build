(defproject clojurescript-build "0.1.0-SNAPSHOT"
  :description "FIXME: write this!"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2411"]
                 #_[sablono "0.2.22"]]

  :source-paths ["src"]

  :test-paths ["test" "test/src"]
  
  :repl-options {
                 :init-ns clojurescript-build.core
                 })

(defproject clojurescript-build "0.1.9"
  :description "A clojurescript build library."
  :url "https://github.com/bhauman/clojurescript-build"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]]

  :source-paths ["src"]

  :test-paths ["test" "test/src"]
  
  :profiles {
    :dev {
      :repl-options { :init-ns clojurescript-build.core }
  }})


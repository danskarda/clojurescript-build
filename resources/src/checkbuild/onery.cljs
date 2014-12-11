(ns checkbuild.onery
  (:require-macros
     [checkbuild.macros :as mac]))


(defn this-is-stupid-really []
  (print "hi there")

  (mac/three 3))

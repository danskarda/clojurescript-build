(ns checkbuild.helper
  (:require-macros
   [checkbuild.macros :as mac]
   [checkbuild.macros-again :as maca]))


(defn helper []
  (mac/three 3)
  (maca/three-again 3)  
  (+ 1 2))

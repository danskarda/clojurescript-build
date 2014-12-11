(ns checkbuild.macros
  (:require [checkbuild.mhelp :refer [three-help]]))




(defmacro three [a]
  `(list ~(three-help a)  ~(three-help a) ~(three-help a)))





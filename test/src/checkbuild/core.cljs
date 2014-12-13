(ns checkbuild.core
  (:require
   [sablono.core]
   [om.core]
   [figwheel.client :as fw]
   [checkbuild.onery]
   [checkbuild.helper :as helper])
  (:require-macros
     [checkbuild.macros :as mac]))


(defn h []
  (checkbuild.onery/this-is-stupid-really)
  (helper/helper))



(enable-console-print!)



;; define your app data so that it doesn't get over-written on reload
;; (defonce app-data (atom {}))

(println "Edits to this text should show up in your developer console now yeah.")

(print (prn-str (mac/three 3)))


(fw/start {
  :on-jsload (fn []
               ;; (stop-and-start-my app)
               )})

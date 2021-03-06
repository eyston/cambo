(ns examples.todo
  (:require-macros [cambo.component.macros :refer [defcomponent defcontainer]])
  (:require [cambo.component :as comp :refer [props get-fragment expand-fragment]]
            [cambo.core :as core]
            [cambo.model :as model]
            [cambo.graph :as graph]
            [cljsjs.react]
            [cljsjs.react.dom]
            [cljs.pprint :refer [pprint]]))

(enable-console-print!)

(defn tag [tag]
  (fn [props & children]
    (apply js/React.createElement tag props children)))

(def div (tag "div"))
(def pre (tag "pre"))
(def h1 (tag "h1"))

(defcontainer QuoteDetails
              {:fragments (fn [_]
                            {:quote [:quote/term
                                     :quote/policy
                                     :quote/monthly-premium
                                     {:quote/carrier [:carrier/name
                                                      :carrier/description]}]
                             :user  [:user/name]})
               :component (component
                            (render [this]
                                    (div nil
                                         (h1 nil "QuoteDetails")
                                         (pre nil (with-out-str
                                                    (cljs.pprint/pprint (props this)))))))})

(def quote-details (comp/factory QuoteDetails))

(defcontainer UserQuote
              {:fragments (fn [_]
                            {:user [:user/name
                                    :user/age
                                    :user/gender
                                    (get-fragment QuoteDetails :user)
                                    {:user/quote [(get-fragment QuoteDetails :quote)]}]})
               :component (component
                            (render [this]
                                    (let [{:keys [user]} (props this)
                                          quote (get-in user [:user/quote])]
                                      (div nil
                                           (h1 nil "UserQuote")
                                           (pre nil (with-out-str
                                                      (cljs.pprint/pprint (props this))))
                                           (quote-details {:user user
                                                           :quote quote
                                                           :look-ma "no-computed"})))))})

(defonce ds (-> (graph/set {} [{:current-user (core/ref [:user/by-id 1])}
                               {:user/by-id {1 {:user/name   "Huey"
                                                :user/age    13
                                                :user/gender :gender/male
                                                :user/quote  (core/ref [:quote/by-id 13])}}}
                               {:quote/by-id {13 {:quote/term 10
                                                  :quote/policy 250000
                                                  :quote/monthly-premium 13.52
                                                  :quote/carrier (core/ref [:carrier/by-code :lddr])}}}
                               {:carrier/by-code {:lddr {:carrier/name "Ladder"
                                                         :carrier/description "11 badasses"}}}])
                :graph
                (graph/as-datasource)))

(defonce model (model/model {:datasource ds}))

(js/ReactDOM.render
  (comp/renderer {:queries {:user [:current-user]}
                  :container UserQuote
                  :model model})
  (.getElementById js/document "app"))

(defonce subscription (let [cb (fn []
                                 (println "cache changed"))]
                        (model/subscribe model cb)))

(defonce interval (js/setInterval
           (fn []
             ;; TODO: lol add get-value / get-cache-value method
             (let [age (get-in (model/get-cache model [[:user/by-id 1 :user/age]])
                               [:user/by-id 1 :user/age])]
               ;; TODO: lol add set-value / set-cache-value method
               (model/set-cache model [{:user/by-id {1 {:user/age (inc age)}}}])))
           2000))

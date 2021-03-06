(ns com.athuey.cambo.router-test
  (:refer-clojure :exclude [get range atom ref])
  (:require [com.athuey.cambo.router :refer :all]
            [com.athuey.cambo.core :as core :refer [range atom ref]]
            [clojure.test :refer :all]))

(deftest route-hash-test
         (is (= [:foo 0 :com.athuey.cambo.router/i "bar"]
                (route-hash [:foo 0 INTEGERS "bar"])))
         (is (= (route-hash [:foo 0 INTEGERS "bar"])
                (route-hash [:foo 0 RANGES "bar"]))))

(deftest expand-routeset-test
  (is (= [[:user/by-id 0 :name]
          [:user/by-id 0 :age]
          [:user/by-id 1 :name]
          [:user/by-id 1 :age]
          [:users 0 :name]
          [:users 0 :age]
          [:users 1 :name]
          [:users 1 :age]]
         (expand-routeset [[:user/by-id :users] [0 1] [:name :age]]))))

(deftest route-tree-test
  (let [route1 {:route [:users/by-id RANGES [:name :age]]}
        route2 {:route [:users]}]
    (is (= {:users/by-id {RANGES {:name {match-key route1}
                                 :age {match-key route1}}}
            :users {match-key route2}}
           (clojure.walk/postwalk #(cond-> % (map? %) (dissoc id-key))
                                  (route-tree [route1 route2])))))
  (is (thrown? Exception
               (let [route1 {:route [:users/by-id RANGES [:name :age]]}
                     route2 {:route [:users/by-id INTEGERS [:name :age]]}]
                 (route-tree [route1 route2])))))

(deftest strip-path-test
  (testing "simple keys"
    (is (= [[:a :b :c]
            []]
           (strip-path [:a :b :c]
                       [:a :b :c]))))
  (testing "simple keys with route token"
    (is (= [[:a :b :c]
            []]
           (strip-path [:a :b :c]
                       [:a KEYS :c]))))
  (testing "path with array args"
    (is (= [[:a [:b :d] :c]
            []]
           (strip-path [:a [:b :d] :c]
                       [:a KEYS :c]))))
  (testing "path with range args"
    (is (= [[:a [0 1 2 3 4 5] :c]
            []]
           (strip-path [:a (range 0 6) :c]
                       [:a RANGES :c]))))
  (testing "path with array keys"
    (is (= [[:a :b :c]
            [[:a :d :c]]]
           (strip-path [:a [:b :d] :c]
                       [:a :b :c]))))
  (testing "path with range"
    (is (= [[:a 1 :c]
            [[:a [0 2 3 4 5] :c]]]
           (strip-path [:a (range 0 6) :c]
                       [:a 1 :c]))))
  (testing "path with array range"
    (is (= [[:a 1 :c]
            [[:a [0 2 5] :c]]]
           (strip-path [:a [(range 0 3) (range 5 6)] :c]
                       [:a 1 :c]))))
  (testing "path with complement partial match"
    (is (= [[:a :c :e]
            [[:b [:c :d] [:e :f]]
             [:a :d [:e :f]]
             [:a :c :f]]]
           (strip-path [[:a :b] [:c :d] [:e :f]]
                       [:a :c :e])))))


(deftest get-test
  (let [noop (fn [& _])
        video-routes {:summary (fn [f]
                                 [{:route [:videos :summary]
                                   :get (fn [path]
                                          (when f (f path))
                                          [{:path [:videos :summary]
                                            :value (atom 75)}])}])}
        precedence-router (fn [on-title on-rating]
                            (router [{:route [:videos INTEGERS :title]
                                      :get (fn [[_ ids _ :as path]]
                                             (when on-title (on-title path))
                                             (for [id ids]
                                               {:path [:videos id :title]
                                                :value (str "title " id)}))}
                                     {:route [:videos INTEGERS :rating]
                                      :get (fn [[_ ids _ :as path]]
                                             (when on-rating (on-rating path))
                                             (for [id ids]
                                               {:path [:videos id :rating]
                                                :value (str "rating " id)}))}
                                     {:route [:lists KEYS INTEGERS]
                                      :get (fn [[_ ids idxs]]
                                             (for [id ids
                                                   idx idxs]
                                               {:path [:lists id idx]
                                                :value (ref [:videos idx])}))}]))]
    (testing "simple route"
      (let [router (router ((video-routes :summary) noop))]
        (is (= {:videos {:summary (atom 75)}}
               (get router [[:videos :summary]])))))
    (testing "should validate that optimizedPathSets strips out already found data."
      (let [calls (clojure.core/atom 0)
            router (router [{:route [:lists KEYS]
                             :get (fn [[_ ids]]
                                    (for [id ids]
                                      (if (= 0 id)
                                        {:path [:lists id]
                                         :value (ref [:two :be 956])}
                                        {:path [:lists id]
                                         :value (ref [:lists 0])})))}
                            {:route [:two :be INTEGERS :summary]
                             :get (fn [[_ _ ids]]
                                    (for [id ids]
                                      (do
                                        (swap! calls inc)
                                        {:path [:two :be id :summary]
                                         :value "hello world"})))}])
            result (get router [[:lists [0 1] :summary]])]
        (is (= {:lists {0 (ref [:two :be 956])
                        1 (ref [:lists 0])}
                :two {:be {956 {:summary (atom "hello world")}}}}
               result))
        (is (= 1 @calls))))
    (testing "should do precedence stripping."
      (let [rating (clojure.core/atom 0)
            title (clojure.core/atom 0)
            router (precedence-router
                     (fn [path]
                       (swap! title inc)
                       (is (= [:videos [123] :title]
                              path)))
                     (fn [path]
                       (swap! rating inc)
                       (is (= [:videos [123] :rating]
                              path))))
            results (gets router [[:videos 123 [:title :rating]]])
            result (first results)]
        (is (= 1 (count results)))
        (is (= {:videos {123 {:title (atom "title 123")
                              :rating (atom "rating 123")}}}
               result))
        (is (= 1 @title))
        (is (= 1 @rating))))
    (testing "should do precedence matching."
      (let [specific (clojure.core/atom 0)
            keys (clojure.core/atom 0)
            router (router [{:route [:a :specific]
                             :get (fn [_]
                                    (swap! specific inc)
                                    [{:path [:a :specific]
                                      :value "hello world"}])}
                            {:route [:a KEYS]
                             :get (fn [_]
                                    (swap! keys inc)
                                    [{:path [:a :specific]
                                      :value "hello world"}])}])
            _ (get router [[:a :specific]])]
        (is (= 1 @specific))
        (is (= 0 @keys))))
    (testing "should grab a reference."
      (let [router (precedence-router nil nil)
            results (gets router [[:lists :abc 0]])]
        (is (= 1 (count results)))
        (is (= {:lists {:abc {0 (ref [:videos 0])}}}
               (last results)))))
    (testing "should not follow references if no keys specified after path to reference"
      (let [router (router [{:route [:products-by-id KEYS KEYS]
                             :get (fn [_] (throw (ex-info "reference followed in error" {})))}
                            {:route [:proffers-by-id INTEGERS :products-list RANGES]
                             :get (fn [_] [{:path [:proffers-by-id 1 :products-list 0]
                                            :value (ref [:products-by-id "CSC1471105X"])}
                                           {:path [:proffers-by-id 1 :products-list 1]
                                            :value (ref [:products-by-id "HON4033T"])}])}])]
        (is (= {:proffers-by-id {1 {:products-list {0 (ref [:products-by-id "CSC1471105X"])
                                                    1 (ref [:products-by-id "HON4033T"])}}}}
               (get router [[:proffers-by-id 1 :products-list (range 0 2)]])))))))

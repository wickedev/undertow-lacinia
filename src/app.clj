(ns app
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [ring.adapter.jetty :as jetty]
            [com.walmartlabs.lacinia.util :as util]
            [ring.middleware.content-type :as content-type]
            [ring.middleware.not-modified :as not-modified]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.spec :as rs]
            [ring.adapter.undertow :refer [run-undertow]]
            [ring.middleware.cookies :as cookies]
            [ring.middleware.cors :refer [wrap-cors]]))

(defn resolver-map
  []
  {:query/search (fn [context args value]
                   "Greeting")})

(defn load-schema
  []
  (-> (io/resource "schema/search.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolver-map))
      schema/compile))

(defn variables
  [req]
  (get-in req [:body-params :variables]))

(defn- build-plain-query-variables [request]
  {:query     (get-in request [:body-params :query])
   :variables (variables request)})

(defn graphql-handler []
  (let [schema (load-schema)]
    (fn [request]
      (prn :request request)
      (let [request' (assoc request :body-params (-> (:body request)
                                                     slurp
                                                     (json/read-str :key-fn keyword)))

            {:keys [query variables]} (build-plain-query-variables request')
            result (lacinia/execute schema query variables nil)]
        result))))

(defn graphql-routes []
  ["/graphql" {:post {:summary    "graphql handler"
                      :responses  {200 [:map
                                        [:body any?]]}
                      :parameters {:multipart [:map
                                               [:operations {:optional true} string?]
                                               [:map {:optional true} string?]]}
                      :handler    (graphql-handler)
                      :middleware []}}])

(defn router-config []
  {:data      {:validate   rs/validate
               :muuntaja   m/instance
               :middleware [parameters/parameters-middleware
                            muuntaja/format-middleware
                            muuntaja/format-negotiate-middleware
                            muuntaja/format-request-middleware
                            muuntaja/format-response-middleware
                            coercion/coerce-exceptions-middleware
                            coercion/coerce-request-middleware
                            coercion/coerce-response-middleware
                            cookies/wrap-cookies]}})


(defn app
  []
  (-> (ring/ring-handler
       (ring/router
        [(graphql-routes)
         (router-config)]))
      (wrap-cors
       :access-control-allow-origin [#".*"]
       :access-control-allow-credentials ["true"]
       :access-control-allow-methods [:options :head :get :post :delete :put])
      content-type/wrap-content-type
      not-modified/wrap-not-modified))

(defn main [& args]
  (run-undertow (app) {:port 8080})
  #_(jetty/run-jetty (app) {:port 8080}))
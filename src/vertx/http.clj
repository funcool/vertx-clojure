;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.http
  "Enables `raw` access to the http facilites of vertx. If you want more
  clojure idiomatic api, refer to the `vertx.web` namespace."
  (:require [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [vertx.util :as vu])
  (:import
   java.util.Map$Entry
   clojure.lang.MapEntry
   io.vertx.core.Vertx
   io.vertx.core.Verticle
   io.vertx.core.Handler
   io.vertx.core.Future
   io.vertx.core.MultiMap
   io.vertx.core.Context
   io.vertx.core.buffer.Buffer
   io.vertx.core.http.HttpServer
   io.vertx.core.http.HttpServerRequest
   io.vertx.core.http.HttpServerResponse
   io.vertx.core.http.HttpServerOptions))

(declare opts->http-server-options)
(declare resolve-handler)

;; --- Public Api

(declare -handle-response)
(declare -handle-body)

(defn ->headers
  [^HttpServerRequest request]
  (let [headers (.headers request)
        it (.iterator ^MultiMap headers)]
    (loop [m (transient {})]
      (if (.hasNext it)
        (let [^Map$Entry me (.next it)
              key (.toLowerCase (.getKey me))
              val (.getValue me)]
          (recur (assoc! m key val)))
        (persistent! m)))))

(defn- ->request
  [^HttpServerRequest request]
  {:method (-> request .rawMethod .toLowerCase keyword)
   :path (.path request)
   :headers (->headers request)
   ::request request
   ::response (.response request)})

(defn handler
  [vsm f]
  (reify Handler
    (handle [this request]
      (let [ctx (->request request)]
        (-handle-response (f ctx) ctx)))))

(s/def :vertx.http/handler fn?)
(s/def :vertx.http/host string?)
(s/def :vertx.http/port pos?)
(s/def ::server-options
  (s/keys :req-un [:vertx.http/handler]
          :opt-un [:vertx.http/host
                   :vertx.http/port]))

(defn server
  "Starts a vertx http server."
  [vsm {:keys [handler] :as options}]
  (s/assert ::server-options options)
  (let [^Vertx vsm (vu/resolve-system vsm)
        ^HttpServerOptions opts (opts->http-server-options options)
        ^HttpServer srv (.createHttpServer vsm opts)
        ^Handler handler (resolve-handler handler)]
    (doto srv
      (.requestHandler handler)
      (.listen))
    srv))

;; --- Impl

(defn- opts->http-server-options
  [{:keys [host port]}]
  (let [opts (HttpServerOptions.)]
    (.setReuseAddress opts true)
    (.setReusePort opts true)
    (.setTcpNoDelay opts true)
    (.setTcpFastOpen opts true)
    (when host (.setHost opts host))
    (when port (.setPort opts port))
    opts))

(defn- resolve-handler
  [handler]
  (cond
    (fn? handler) (vu/fn->handler handler)
    (instance? Handler handler) handler
    :else (throw (ex-info "invalid handler" {}))))

(defprotocol IAsyncResponse
  (-handle-response [_ _]))

(defprotocol IAsyncBody
  (-handle-body [_ _]))

(extend-protocol IAsyncResponse
  java.util.concurrent.CompletionStage
  (-handle-response [data ctx]
    (p/then' data #(-handle-response % ctx)))

  clojure.lang.IPersistentMap
  (-handle-response [data ctx]
    (let [status (or (:status data) 200)
          body (:body data)
          res (::response ctx)]
      (.setStatusCode ^HttpServerResponse res status)
      (-handle-body body res))))

(extend-protocol IAsyncBody
  (Class/forName "[B")
  (-handle-body [data res]
    (.end ^HttpServerResponse res (Buffer/buffer data)))

  Buffer
  (-handle-body [data res]
    (.end ^HttpServerResponse res ^Buffer data))

  nil
  (-handle-body [data res]
    (.putHeader ^HttpServerResponse res "content-length" "0")
    (.end ^HttpServerResponse res))

  String
  (-handle-body [data res]
    (let [length (count data)]
      (.putHeader ^HttpServerResponse res "content-length" (str length))
      (.end ^HttpServerResponse res data))))

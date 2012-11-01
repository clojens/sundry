(ns sundry.ring
  (:require [sundry.http :refer [max-age->expires]]
            [sundry.core :refer [compile-if]]
            [sundry.ring.stateful-session :as sess]
            [clojure.repl :as repl]))

(defn wrap-if [handler pred wrapper & args]
  (if pred
    (apply wrapper handler args)
    handler))

(defn- default-logger [msg & vals]
  (let [line (apply format msg vals)]
    (locking System/out (println line))))

(defn stacktrace-str [e]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (repl/pst e 1000)
      (str sw))))

(defn wrap-exception-logging
  ([handler]
    (wrap-exception-logging handler default-logger))
  ([handler logger]
    (fn [req]
      (try
        (handler req)
        (catch Exception e
          (logger "Exception:\n%s" (stacktrace-str e))
          (throw e))))))

(defn wrap-failsafe
  ([handler]
    (wrap-failsafe (constantly "<h1>Oops</h1><p>Something went wrong.</p>")))
  ([handler body-renderer]
    (fn [req]
      (try
        (handler req)
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "text/html"}
           :body (body-renderer req)})))))

(defn wrap-stacktrace [handler]
  (fn [req]
    (try
      (handler req)
      (catch Exception e
        {:status 500
         :headers {"Content-Type" "text/html"}
         :body (str "<h1>Oops</h1><p>Something went wrong.</p>"
                    "<pre>"
                    (stacktrace-str e)
                    "</pre>") }))))

(def ^:dynamic *req* nil)

(defn wrap-request [handler]
  (fn [req]
    (binding [*req* req]
      (handler req))))

(defn wrap-cache-control [handler max-age]
  (fn [req]
    (when-let [resp (handler req)]
      (assoc-in resp [:headers "Expires"]
                (max-age->expires max-age)))))

(defn wrap-ajax-detect [handler]
  (fn [req]
    (handler (assoc req
               :ajax (= "XMLHttpRequest"
                        (get-in req [:headers "x-requested-with"]))))))

(def ^:dynamic *start-time* nil)

(defn wrap-timer [handler]
  (fn [req]
    (binding [*start-time* (System/nanoTime)]
      (handler req))))

(defn elapsed-ms []
  (/ (double (- (System/nanoTime) *start-time*)) 1000000.0))


(compile-if (do (require 'ring.middleware.session
                         'ring.middleware.session.cookie)
              true)
  (defn make-cookie-session-wrapper
    [& {:keys [path key cookie-name expires]}]
    (let [expires (or expires (max-age->expires (* 60 60 24 365)))
          sess-opts {:store (if key
                              (ring.middleware.session.cookie/cookie-store
                                {:key key})
                              (ring.middleware.session.cookie/cookie-store))
                     :cookie-attrs {:expires expires}}
          sess-opts (if path
                      (assoc sess-opts :root path)
                      sess-opts)
          sess-opts (if cookie-name
                      (assoc sess-opts :cookie-name cookie-name)
                      sess-opts)]
      (fn [handler]
        (ring.middleware.session/wrap-session handler sess-opts)))))

(def wrap-stateful-session sess/wrap-stateful-session)
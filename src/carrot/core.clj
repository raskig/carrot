(ns carrot.core (:require
            [dire.core :refer [with-handler!]]
            [langohr.basic :as lb]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]))

(defn- retry-exception?
  "returns true if the exception has to be retried, false otherwise"
  [exception logger-fn]
  (let [reject? (:reject? (ex-data exception))] ;; reject? = don't retry
    (when (and logger-fn reject?)
       (logger-fn "METRIC ns=carrot.core name=rejecting-exception exception=" exception))
    (not reject?)))

(defn- add-exception-data
  "given an exception e, returns a ClojureInfo exceptionthat contains the extra data d.
  previous data of e should be kept."
  [e d]
  ;; TODO figure out if we can keep the stacktrace as well
  (ex-info (or (.getMessage e) "do not retry me")
           (-> (or (ex-data e) {})
               (merge d))))


(defn- throw-with-extra!
  "given a function f attaches an error handler
  that will catch all exceptions and rethrow them with added data {:reject? true}"
  [f extra]
  (with-handler! f
    Exception
    (fn [e & args]
      (throw (add-exception-data e extra)))))


(defn do-not-retry!
  "attach a supervisor that will not retry the functions in fn-coll"
  [fn-coll]
  (doall (map #(throw-with-extra! % {:reject? true}) fn-coll)))

(defn throw-do-not-retry-exception [msg meta]
  (throw (ex-info msg
                  (assoc meta :reject? true ))))


(defn- nack [ch message meta routing-key retry-attempts max-retry waiting-exchange dead-letter-exchange logger-fn]
  (let [retry-attempts (int retry-attempts)
        exchange (if (> max-retry retry-attempts) waiting-exchange dead-letter-exchange)]
    (when logger-fn (logger-fn "LOGME ns=carrot.core name=current-retry-attempt message:" (:message-id meta) retry-attempts))
    (when logger-fn (logger-fn "Sending message " (:message-id meta)  " to the exchange: " exchange))
    (lb/publish ch
                exchange
                routing-key
                message
                {:persistent true
                 :headers {"retry-attempts" (inc retry-attempts)}})))

(defn- message-handler [message-handler routing-key max-retry waiting-exchange dead-letter-exchange logger-fn ch meta ^bytes payload]
  (try
    (let [carrot-map {:channel ch
                      :meta meta
                      :payload payload}]
      (-> carrot-map
          message-handler)
      (lb/ack ch (:delivery-tag meta))
      (when logger-fn (logger-fn "LOGME ns=carrot.core name=message-processed message-id=" (:message-id meta))))
    (catch Exception e
      (when logger-fn
        (logger-fn "LOGME ns=carrot.core name=message-process-error message-id="(:message-id meta) "error="e " exception="(clojure.stacktrace/print-stack-trace e)))
      (if (retry-exception? e logger-fn)
        (nack ch payload meta routing-key (or (get (:headers meta) "retry-attempts") 0) max-retry waiting-exchange dead-letter-exchange logger-fn)
        (lb/ack ch (:delivery-tag meta))))))


(defn declare-system [channel
                      {:keys [waiting-exchange dead-letter-exchange waiting-queue message-exchange]}
                      message-ttl
                      exchange-type
                      exchange-config]
  (le/declare channel waiting-exchange exchange-type exchange-config)
  (le/declare channel message-exchange exchange-type exchange-config)
  (le/declare channel dead-letter-exchange exchange-type exchange-config)
  (let [waiting-queue-name (:queue (lq/declare channel waiting-queue
                                 {:exlusive false
                                  :auto-delete true
                                  :durable true
                                  :arguments {"x-message-ttl" message-ttl
                                              "x-dead-letter-exchange" message-exchange}}))]
    (lq/bind channel waiting-queue-name waiting-exchange {:routing-key "#"})))

(defn subscribe [channel
                 {:keys [dead-letter-exchange]}
                 queue-name
                 message-handler
                 queue-config]
  (lc/subscribe channel queue-name message-handler queue-config)
  (lq/declare channel (str "dead-" queue-name) {:exclusive false :auto-delete false})
  (lq/bind channel (str "dead-" queue-name) dead-letter-exchange {:routing-key queue-name}))

(defn crate-message-handler-function
  ([handler routing-key max-retry {:keys [waiting-exchange dead-letter-exchange]} logger-fn]
   (partial message-handler handler routing-key max-retry waiting-exchange dead-letter-exchange logger-fn))
  ([handler routing-key max-retry {:keys [waiting-exchange dead-letter-exchange]}]
   (crate-message-handler-function message-handler handler routing-key max-retry waiting-exchange dead-letter-exchange nil)))

(defmacro compose-payload-handler-function
  [& args]
  (list 'fn ['payload]
        (concat (list '-> 'payload)
                args)))

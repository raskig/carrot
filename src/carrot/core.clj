(ns carrot.core (:require
            [dire.core :refer [with-handler!]]
            [langohr.basic :as lb]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]
            [carrot.exp-backoff :as exp-backoff]
            [carrot.delayed-retry :as delayed-retry]))

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




(defn- nack [ch message meta routing-key retry-attempts carrot-system logger-fn]
  (if (= :exp-backoff (get-in carrot-system [:retry-config :strategy]))
    (exp-backoff/nack ch message meta routing-key retry-attempts carrot-system logger-fn)
    (delayed-retry/nack ch message meta routing-key retry-attempts carrot-system logger-fn)))




(defn- message-handler [message-handler routing-key carrot-system logger-fn ch meta ^bytes payload]
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
        (nack ch payload meta routing-key (or (get (:headers meta) "retry-attempts") 0) carrot-system logger-fn)
        (lb/ack ch (:delivery-tag meta))))))


(defn crate-message-handler-function
  ([handler routing-key carrot-system logger-fn]
   (partial message-handler handler routing-key carrot-system logger-fn))
  ([handler routing-key carrot-system]
   (crate-message-handler-function message-handler handler routing-key carrot-system nil)))








(defn declare-system
  ([channel
    carrot-system
    exchange-type
    exchange-config
    waiting-queue-config]
   (case (get-in carrot-system [:retry-config :strategy])
     :simple-backoff (delayed-retry/declare-system channel
                                carrot-system
                                exchange-type
                                exchange-config
                                waiting-queue-config)
     :exp-backoff (exp-backoff/declare-system channel
                                         carrot-system
                                         exchange-type
                                         exchange-config
                                         waiting-queue-config)))
  ([channel
    carrot-system
    exchange-type
    exchange-config]
   (declare-system channel
                   carrot-system
                   exchange-type
                   exchange-config
                   {})))

(defn destroy-system [channel {:keys [waiting-exchange dead-letter-exchange waiting-queue message-exchange]} queue-name-coll]
  (map #(lq/delete channel %) queue-name-coll)
  (map #(lq/delete channel (str "dead-" %)) queue-name-coll)
  (lq/delete channel waiting-queue)
  (le/delete channel waiting-exchange)
  (le/delete channel dead-letter-exchange)
  (le/delete channel message-exchange))


(defn subscribe
  ([channel
    {:keys [dead-letter-exchange]}
    queue-name
    message-handler
    queue-config
    dead-queue-config-function]
   (lc/subscribe channel queue-name message-handler queue-config)
   (lq/declare channel (str "dead-" queue-name)
               (merge-with merge (dead-queue-config-function queue-name)
                           {:exclusive false
                            :auto-delete false}))
   (lq/bind channel (str "dead-" queue-name) dead-letter-exchange {:routing-key queue-name}))
  ([channel
    carrot-config
    queue-name
    message-handler
    queue-config]
   (subscribe channel
              carrot-config
              queue-name
              message-handler
              queue-config
              {})))

(defmacro compose-payload-handler-function
  [& args]
  (list 'fn ['payload]
          (concat (list '-> 'payload)
                args)))

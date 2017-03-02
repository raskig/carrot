(ns carrot.rabbit
  (:require [cheshire.core :as json]
            [cljrc-automod.coerce :as coerce]
            [cljrc-automod.comment :refer [handle-comment handle-facebook]]
            [cljrc-automod.comment.rabbit-constants :as rc]
            [cljrc-automod.facebook]
            [cljrc-automod.report-abuse :refer [handle-abuse-report]]
            [cljrc-automod.schema :as xs]
            [cljrc-automod.vote :refer [handle-voteup
                                        handle-votedown
                                        handle-unvoteup
                                        handle-unvotedown]]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :refer [Lifecycle]]
            [dire.core :refer [with-handler!]]
            [langohr.basic :as lb]
            [langohr.channel :as lch]
            [langohr.consumers :as lc]
            [langohr.core :as rmq]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [perseverance.core :as p]
            [schema.core :as s]))

(defn- retry-exception?
  "returns true if the exception has to be retried, false otherwise"
  [exception]
  (let [reject? (:reject? (ex-data exception))] ;; reject? = don't retry
    (when reject?
      (log/debug "METRIC ns=rc.automod name=rejecting-exception exception=" exception))
    (not reject?)))

(defn add-exception-data
  "given an exception e, returns a ClojureInfo exceptionthat contains the extra data d.
  previous data of e should be kept."
  [e d]
  ;; TODO figure out if we can keep the stacktrace as well
  (ex-info (or (.getMessage e) "do not retry me")
           (-> (or (ex-data e) {})
               (merge d))))

(defn throw-with-extra!
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

(do-not-retry! [#'cljrc-automod.comment/add-user-to-comment
                #'cljrc-automod.facebook/call-facebook-feed])

;; TODO: Need to consider how to handle errors. At present the handler simply
;; acks after a single retry. We may wish to move to some queue to retry at a
;; later a date. Also, it would be good log additional info for splunk searches,
(defn- handle-message
  ""
  [handler coerce-fn ch meta ^bytes payload]
  (try
    (let [string (String. payload "UTF-8")
          _ (log/info "METRIC ns=rc.automod name=message-received msg=" string)]
      (-> string
          (json/parse-string true)
          (coerce/clojurize-keys)
          (coerce-fn)
          handler)
      (lb/ack ch (:delivery-tag meta))
      (log/info "METRIC ns=rc.automod name=message-processed msg=" string))
    (catch Exception e
      (if (:redelivery? meta)
        (log/error "METRIC ns=rc.automod name= message-id="(:message-id meta) "error="e "exception="(clojure.stacktrace/print-stack-trace e)) ;; No message acknowledgement.
        (log/error "METRIC ns=rc.automod name=message-first-attempt--error message-id="(:message-id meta) "error="e "exception="(clojure.stacktrace/print-stack-trace e)))
      (if (retry-exception? e)
        (lb/nack ch (:delivery-tag meta) false true)
        (lb/ack ch (:delivery-tag meta))))))

(defn- create-rabbit-connection [rabbit-config]
  (log/info "Rabbit config:" rabbit-config)
  (let [rconn (rmq/connect rabbit-config)
        rch (lch/open rconn)]
    (try
      (le/declare rch rc/message-exchange "topic" {:durable true})
      {:connection rconn :channel rch}
      (catch Exception e
        (log/info "error: " (.getMessage e))
        (when (and rch (.isOpen rch))
          (rmq/close rch))
        (when (and rconn (.isOpen rconn))
          (rmq/close rconn))
        :error))))

(defn create-consumer [ch h routing-key queue-name config]
  (let [qn (:queue (lq/declare ch queue-name
                               {:exlusive false
                                :auto-delete true
                                :durable false
                                :arguments {"x-message-ttl" (or (:message-ttl config) 5000)
                                            "x-dead-letter-exchange" rc/dead-letter-exchange}}))]
    (lq/bind ch qn rc/message-exchange {:routing-key routing-key})
    (lc/subscribe ch qn h {:auto-ack false})))

(defn- assert-mq-settings [{:keys [host consumer-id]}]
  (when (or (empty? host) (empty? consumer-id))
    (throw (Exception. (format "Rabbit-mq config error - ensure :host and :consumer-id are set. Sup(plied values :host - %s, :consumer-id - %s"
                               host
                               consumer-id)))))

(defrecord RabbitMqClient [config]
  Lifecycle

  (start [component]
    ;;(assert-mq-settings config)
    (let [rabbit (create-rabbit-connection config)]
      (doseq [[queue routing-key handler schema]
              [["post.comment" "post.comment" handle-comment xs/RabbitComment]
               ["post.abuse.report" "post.abuse.report" handle-abuse-report xs/RabbitAbuseReport]
               ["post.voteup" "post.voteup" handle-voteup xs/RabbitCommentVote]
               ["post.votedown" "post.votedown" handle-votedown xs/RabbitCommentVote]
               ["post.unvoteup" "post.unvoteup" handle-unvoteup xs/RabbitCommentVote]
               ["post.unvotedown" "post.unvotedown" handle-unvotedown xs/RabbitCommentVote]
               ["post.moderation.facebook" "post.moderation.facebook" handle-facebook xs/RabbitFbComment]]]
        (create-consumer (:channel rabbit) (partial handle-message handler (coerce/parse-rabbit schema)) routing-key queue config))

      (assoc component
        :rabbit-connection (:connection rabbit)
        :rabbit-channel (:channel rabbit))))

  (stop [{:keys [rabbit-connection rabbit-channel :as component]}]
    (when (and rabbit-channel (.isOpen rabbit-channel))
      (rmq/close rabbit-channel))
    (when (and rabbit-connection (.isOpen rabbit-connection))
      (rmq/close rabbit-connection))
    (dissoc component :rabbit-connection :rabbit-channel)))

;; (defn publish-message [msg]
;;   (lb/publish import-broker-ch "" (format "AssetImport.%s" mq-consumer-id)
;;               (json/generate-string {})
;;               {:persistent true}))

(defn make-rabbit-client [config]
  (RabbitMqClient. config))

(comment
  (require 'cljrc-automod.system)

  (lb/publish (:rabbit-channel (cljrc-automod.system/component :rabbit)) "post.comment" "post.comment" (json/generate-string {:comment-id 123})))

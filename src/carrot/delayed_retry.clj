(ns carrot.delayed-retry (:require
            [dire.core :refer [with-handler!]]
            [langohr.basic :as lb]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]))


(defn nack [ch message meta routing-key retry-attempts {:keys [retry-exchange dead-letter-exchange message-exchange retry-config]} logger-fn]
  (let [max-retry (:max-retry-count retry-config)
        retry-attempts (int retry-attempts)
        exchange (if (> max-retry retry-attempts) retry-exchange dead-letter-exchange)]
    (when logger-fn (logger-fn "LOGME ns=carrot.core name=current-retry-attempt delayed-retry message:" (:message-id meta) retry-attempts))
    (when logger-fn (logger-fn "Sending message " (:message-id meta)  " to the exchange: " exchange))
    (lb/publish ch
                exchange
                routing-key
                message
                (merge
                 meta
                 {:persistent true
                  :headers {"retry-attempts" (inc retry-attempts)}})))
  (lb/ack ch (:delivery-tag meta)))


(defn declare-system [channel
                      {:keys [retry-exchange dead-letter-exchange retry-queue message-exchange retry-config exchange-type exchange-config retry-queue-config]
                       :or {:retry-queue-config {}}}]
  (le/declare channel retry-exchange exchange-type exchange-config)
  (le/declare channel message-exchange exchange-type exchange-config)
  (le/declare channel dead-letter-exchange exchange-type exchange-config)
  (let [retry-queue-name (:queue (lq/declare channel retry-queue
                                               (merge-with merge
                                                           retry-queue-config
                                                           {:exlusive false
                                                            :auto-delete true
                                                            :durable true
                                                            :arguments {"x-message-ttl" (:message-ttl retry-config)
                                                                        "x-dead-letter-exchange" message-exchange}})))]
    (lq/bind channel retry-queue-name retry-exchange {:routing-key "#"})))

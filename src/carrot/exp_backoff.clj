(ns carrot.exp-backoff (:require
            [dire.core :refer [with-handler!]]
            [langohr.basic :as lb]
            [langohr.queue :as lq]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]))


(defn next-ttl [retry-attempts retry-config]
  (int (min (* (.pow (BigInteger. "2") (int retry-attempts)) (:initial-ttl retry-config)) (:max-ttl retry-config))))

(defn nack [ch message meta routing-key retry-attempts {:keys [retry-exchange dead-letter-exchange message-exchange retry-config]} logger-fn]
  (let [retry-attempts (int retry-attempts)
        max-retry (:max-retry-count retry-config)
        exchange (if (> max-retry retry-attempts) retry-exchange dead-letter-exchange)
        calculated-ttl (apply (-> retry-config :next-ttl-function) [retry-attempts retry-config])
        exp-retry-queue-name (str routing-key "."  (str calculated-ttl) ".retry" )]
    (when logger-fn (logger-fn "LOGME ns=carrot.core name=current-retry-attempt message:" (:message-id meta) retry-attempts))
    (when logger-fn (logger-fn "Sending message " (:message-id meta)  " to the exchange: " exchange))
    (lq/declare ch exp-retry-queue-name
                {:durable true
                 :auto-delete true
                 :arguments {"x-message-ttl" calculated-ttl
                             "x-expires" (* 100 calculated-ttl)
                             "x-dead-letter-exchange" message-exchange
                             "x-dead-letter-routing-key" routing-key
                             }})
    (lq/bind ch exp-retry-queue-name retry-exchange {:routing-key exp-retry-queue-name})
    (lb/publish ch
                exchange
                (if (= exchange dead-letter-exchange) routing-key exp-retry-queue-name)
                message
                (merge
                 meta
                 {:persistent true
                  :headers {"retry-attempts" (inc retry-attempts)}})))
  (lb/ack ch (:delivery-tag meta)))


(defn declare-system [channel
                      {:keys [retry-exchange dead-letter-exchange message-exchange exchange-type exchange-config retry-queue-config]
                       :or {:retry-queue-config {}}}]
  (le/declare channel retry-exchange exchange-type exchange-config)
  (le/declare channel message-exchange exchange-type exchange-config)
  (le/declare channel dead-letter-exchange exchange-type exchange-config))

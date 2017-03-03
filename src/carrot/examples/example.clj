(ns carrot.examples.example
  (:gen-class)
  (:require [carrot.core :as carrot]
            [langohr.core      :as rmq]
            [langohr.exchange  :as le]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [clojure.tools.logging :as log]))

(def ^{:const true}
  default-exchange-name "")

(defn message-handler
  [{:keys [ch meta payload]}]
  (println (format "[consumer] Received a message: %s"
                   (String. payload "UTF-8")))
  (throw (Exception. "my exception for retry message")))

(defn logger [ & all]
  (log/info all))

(defn- main
  [& args]
  (let [conn  (rmq/connect)
        ch    (lch/open conn)
        qname "message-queue"]
    (println (format "[main] Connected. Channel id: %d" (.getChannelNumber ch)))
    (carrot/declare-system {:channel ch
                            :waiting-exchange "waiting-exchange"
                            :dead-letter-exchange "dead-letter-exchange"
                            :waiting-queue "waiting-queue"
                            :message-exchange "message-exchange"
                            :message-ttl 3000
                            :exchange-type "topic"
                            :exchange-config {:durable true}})
    (lq/declare ch qname {:exclusive false :auto-delete false})
    (lq/bind ch qname "message-exchange" {:routing-key qname})
    (carrot/subscribe {:dead-letter-exchange "dead-letter-exchange"
                       :channel ch
                       :queue-name qname}
                      (carrot/crate-message-handler-function
                       (carrot/compose-payload-handler-function
                        message-handler)
                       qname
                       3
                       "waiting-exchange"
                       "dead-letter-exchange"
                       logger)
                      {:auto-ack true})
    (lb/publish ch default-exchange-name qname "Hello World!" {:content-type "text/plain" :type "greetings.hi"})
    (Thread/sleep 20000)
    (println "[main] Disconnecting...")
    (rmq/close ch)
    (rmq/close conn)))

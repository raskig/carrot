(ns carrot.examples.example-without-retry
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

;;your custom message handler with exception to test retry mechanism with
(defn message-handler-01
  [{:keys [ch meta payload], :as carrot-map}]
  (println (format "[consumer] Received a message: %s"
                   (String. payload "UTF-8")))
  carrot-map)

(defn message-handler-02
  [{:keys [ch meta payload], :as carrot-map}]
  (println (format "[consumer] Received a message: %s"
                   (String. payload "UTF-8")))
  (throw (Exception. "my exception for retry message")))

(carrot/do-not-retry! [#'message-handler-02])

;;ypurlogger function. It is optional
(defn logger [ & all]
  (log/info all))

;;define ypur exchange and queue names in a carrot config map:
(def carrot-system {:retry-config {:strategy :simple-backoff
                                   :message-ttl 3000
                                   :max-retry-count 3}
                    :waiting-exchange "waiting-exchange"
                    :dead-letter-exchange "dead-letter-exchange"
                    :waiting-queue "waiting-queue"
                    :message-exchange "message-exchange"
                    :exchange-type "topic"
                    :exchange-config {:durable true}
                    :waiting-queue-config {:arguments {"x-max-length" 1000}}})


(defn dead-queue-config-function [queue-name]
  {:arguments {"x-max-length" 1000}})

(defn- main
  [& args]
  (let [conn  (rmq/connect)
        channel    (lch/open conn)
        qname "message-queue"]
    (println (format "[main] Connected. Channel id: %d" (.getChannelNumber channel)))
    ;;declare your carrot system
    (carrot/declare-system channel carrot-system)

    ;;declare your queue where you want to send your business messages:
    (lq/declare channel qname {:exclusive false :auto-delete false})
    ;;bind your queue to your main message exchange. Use the same name you defined in carrot system config:
    (lq/bind channel qname "message-exchange" {:routing-key qname})
    (carrot/subscribe channel
                        carrot-system
                        qname
                        (carrot/crate-message-handler-function
                         (comp
                          message-handler-01
                          message-handler-02
                          ;;here you can en list more functions and they will be threaded in order via threading macr
                          ;;and will compose a message handler function
                          )
                         qname
                         carrot-system
                         println)
                        {:auto-ack false}
                        dead-queue-config-function)
    (lb/publish channel default-exchange-name qname "Hello World!" {:content-type "text/plain" :type "greetings.hi" :message-id (str (java.util.UUID/randomUUID))})
    (Thread/sleep 20000)
    (println "[main] Disconnecting...")
    (rmq/close channel)
    (rmq/close conn)))

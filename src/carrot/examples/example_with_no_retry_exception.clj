(ns carrot.examples.example-with-no-retry-exception
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
  (println (format "[consumer] Received a message part 1: %s"
                   (String. payload "UTF-8")))
  carrot-map)

(defn message-handler-02
  [{:keys [ch meta payload], :as carrot-map}]
  (println (format "[consumer] Received a message part 2: %s"
                   payload "U"))
  (carrot/throw-do-not-retry-exception "Something fatal happaned." {:cause "The message can not be processed."})
  carrot-map)


;;yourlogger function. It is optional
(defn logger [ & all]
  (log/info all))

;;define your exchange and queue names in a carrot config map:
(def carrot-config {:waiting-exchange "waiting-exchange"
                    :dead-letter-exchange "dead-letter-exchange"
                    :waiting-queue "waiting-queue"
                    :message-exchange "message-exchange"})

(defn- main
  [& args]
  (let [conn  (rmq/connect)
        ch    (lch/open conn)
        qname "message-queue"]
    (println (format "[main] Connected. Channel id: %d" (.getChannelNumber ch)))
    ;;declare your carrot system
    (carrot/declare-system ch
                           carrot-config
                           3000
                           "topic"
                           {:durable true})

    ;;declare your queue where you want to send your business messages:
    (lq/declare ch qname {:exclusive false :auto-delete false})
    ;;bind your queue to your main message exchange. Use the same name you defined in carrot system config:
    (lq/bind ch qname "message-exchange" {:routing-key qname})
    (carrot/subscribe ch
                      carrot-config
                      qname
                      ;;user carrot to create the message handler for langohr:
                      (carrot/crate-message-handler-function
                       (carrot/compose-payload-handler-function
                        message-handler-01
                        message-handler-02
                        ;;here you can en list more functions and they will be
                        ;;threaded in order via threading macro
                        ;;and will compose a message handler function
                        )
                       qname
                       3
                       carrot-config
                       logger)
                      {:auto-ack false})
    (lb/publish ch default-exchange-name qname "Hello World!" {:content-type "text/plain" :type "greetings.hi"})
    (Thread/sleep 20000)
    (println "[main] Disconnecting...")
    (rmq/close ch)
    (rmq/close conn)))

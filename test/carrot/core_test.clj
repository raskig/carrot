(ns carrot.core-test
  (:refer-clojure :exclude [get declare])
  (:require [langohr.core      :as lhc]
            [langohr.consumers :as lhcons]
            [langohr.queue     :as lhq]
            [langohr.exchange  :as lhe]
            [langohr.basic     :as lhb]
            [langohr.util      :as lhu]
            [carrot.core :as carrot]
            [langohr.channel   :as lch]
            [clojure.test :refer :all])
  (:import [com.rabbitmq.client Connection Channel AMQP
            AMQP$BasicProperties AMQP$BasicProperties$Builder
            QueueingConsumer GetResponse AMQP$Queue$DeclareOk]
           java.util.UUID
           java.util.concurrent.TimeUnit))


(defn dead-queue-config-function [queue-name]
  {:arguments {"x-max-length" 1000}})

(deftest test-subscribe-with-custom-handler2
  (with-open [conn (lhc/connect)
              channel (lch/open conn)]
    (let [qname "message-queue"
          latch (java.util.concurrent.CountDownLatch. 1)
          handler-called (atom #{});;we define this atom which will be a global variable where we store the fact that the message has been consumed ok
          msg-handler   (fn [{:keys [ch meta payload]}]
                          (.countDown latch));;message handler starts  the countdown when message is arrived: we will read the value of atom when the counter is done.
          log-called (fn [tag] (fn [_] (swap! handler-called conj tag)))] ;;when this functin is called we swap the atom: we add the caslled tag
      (def carrot-config {:waiting-exchange "waiting-exchange"
                    :dead-letter-exchange "dead-letter-exchange"
                    :waiting-queue "waiting-queue"
                          :message-exchange "message-exchange"})
      (carrot/declare-system channel
                           carrot-config
                           3000
                           "topic"
                           {:durable true}
                           {:arguments {"x-max-length" 1000}})
      (lhq/declare channel qname {:exclusive false :auto-delete false})
      (lhq/bind channel qname "message-exchange" {:routing-key qname})
      (carrot/subscribe channel
                        carrot-config
                        qname
                        (carrot/crate-message-handler-function
                         msg-handler
                         qname
                         3
                         carrot-config
                         println)
                        {:auto-ack false :handle-consume-ok (log-called :handle-consume-ok)};; consume-ok function is called when message consumption is OK.(thi is in this case the log-called function with a tag.)
                        dead-queue-config-function)
      (lhb/publish channel "message-exchange" qname "dummy payload" { :message-id (str (java.util.UUID/randomUUID))})
      (is (.await latch 700 TimeUnit/MILLISECONDS));;await causes the current thread to wait until the latch has counted down to zero, unless the thread is interrupted
      (is (= #{:handle-consume-ok} @handler-called)))))

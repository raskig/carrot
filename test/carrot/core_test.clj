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


(def carrot-config {:waiting-exchange "waiting-exchange"
                    :dead-letter-exchange "dead-letter-exchange"
                    :waiting-queue "waiting-queue"
                          :message-exchange "message-exchange"})

(deftest destroy
  (with-open [conn (lhc/connect)
              channel (lch/open conn)]
    (carrot/destroy-system channel carrot-config '("message-queue"))))

(defn with-destroy [f]
  (f)
  (destroy))

(use-fixtures :each with-destroy)

(defn dead-queue-config-function [queue-name]
  {:arguments {"x-max-length" 1000}})


(deftest test-retry-with-carrot
  (with-open [conn (lhc/connect)
              channel (lch/open conn)]
    (let [qname "message-queue"
          latch (java.util.concurrent.CountDownLatch. 1);;counter to be called when message handler first called
          latch-dead (java.util.concurrent.CountDownLatch. 1);;counter to be called when message handler for the dead letter queue called
          event-list (atom #{});;we define this atom which will be a global variable where we store the fact that the message has been consumed ok
          msg-handler   (fn [{:keys [ch meta payload]}]
                          (swap! event-list conj (keyword (str "retry-" (or (clojure.core/get (:headers meta) "retry-attempts") 0))))
                          (.countDown latch)
                          (throw (Exception. "my exception for retry message")));;message handler starts  the countdown when message is arrived: we will read the value of atom when the counter is done.
          dead-msg-handler (fn [ch meta payload]
                          (.countDown latch-dead))
          log-called (fn [tag] (fn [_] (swap! event-list conj tag)))] ;;when this functin is called we swap the atom: we add the caslled tag
      (def carrot-config {:retry-config {:strategy :simple-backoff
                                         :message-ttl 3000
                                         :max-retry-count 3}
                        :waiting-exchange "waiting-exchange"
                        :dead-letter-exchange "dead-letter-exchange"
                        :waiting-queue "waiting-queue"
                        :message-exchange "message-exchange"})
      (carrot/declare-system channel
                           carrot-config
                           "topic"
                           {:durable true}
                           {:arguments {"x-max-length" 1000}})
      (lhq/declare channel qname {:exclusive false :auto-delete true})
      (lhq/bind channel qname "message-exchange" {:routing-key qname})
      (carrot/subscribe channel
                        carrot-config
                        qname
                        (carrot/crate-message-handler-function
                         msg-handler
                         qname
                         carrot-config
                         println)
                        {:auto-ack false :handle-consume-ok (log-called :handle-consume-ok)};; consume-ok function is called when message consumption is OK.(thi is in this case the log-called function with a tag.)
                        dead-queue-config-function)
      (lhcons/subscribe channel "dead-message-queue" dead-msg-handler {:auto-ack true :handle-consume-ok (log-called :handle-dead-message-ok)})
      (lhb/publish channel "message-exchange" qname "dummy payload" { :message-id (str (java.util.UUID/randomUUID))})
      (Thread/sleep 10000)
      (is (.await latch 700 TimeUnit/MILLISECONDS));;await causes the current thread to wait until the latch has counted down to zero, unless the thread is interrupted
      (is (.await latch-dead 700 TimeUnit/MILLISECONDS));;await causes the current thread to wait until the latch has counted down to zero, unless the thread is interrupted
      (is (= #{:handle-consume-ok :retry-0 :retry-1 :retry-2 :retry-3 :handle-dead-message-ok} @event-list)))))







(deftest test-expo-retry-with-carrot
  (with-open [conn (lhc/connect)
              channel (lch/open conn)]
    (let [qname "message-queue"
          latch (java.util.concurrent.CountDownLatch. 1);;counter to be called when message handler first called
          latch-dead (java.util.concurrent.CountDownLatch. 1);;counter to be called when message handler for the dead letter queue called
          event-list (atom #{});;we define this atom which will be a global variable where we store the fact that the message has been consumed ok
          msg-handler   (fn [{:keys [ch meta payload]}]
                          (swap! event-list conj (keyword (str "retry-" (or (clojure.core/get (:headers meta) "retry-attempts") 0))))
                          (.countDown latch)
                          (throw (Exception. "my exception for retry message")));;message handler starts  the countdown when message is arrived: we will read the value of atom when the counter is done.
          dead-msg-handler (fn [ch meta payload]
                          (.countDown latch-dead))
          log-called (fn [tag] (fn [_] (swap! event-list conj tag)))] ;;when this functin is called we swap the atom: we add the caslled tag
      (def carrot-config {:retry-config {:strategy :exp-backoff
                                         :initial-ttl 30
                                         :max-retry-count 3}
                          :waiting-exchange "waiting-exchange"
                          :dead-letter-exchange "dead-letter-exchange"
                          :waiting-queue "waiting-queue"
                          :message-exchange "message-exchange"})
      (carrot/declare-system channel
                           carrot-config
                           "topic"
                           {:durable true}
                           {:arguments {"x-max-length" 1000}})
      (lhq/declare channel qname {:exclusive false :auto-delete true})
      (lhq/bind channel qname "message-exchange" {:routing-key qname})
      (carrot/subscribe channel
                        carrot-config
                        qname
                        (carrot/crate-message-handler-function
                         msg-handler
                         qname
                         carrot-config
                         println)
                        {:yauto-ack false :handle-consume-ok (log-called :handle-consume-ok)};; consume-ok function is called when message consumption is OK.(thi is in this case the log-called function with a tag.)
                        dead-queue-config-function)
      (lhcons/subscribe channel "dead-message-queue" dead-msg-handler {:auto-ack true :handle-consume-ok (log-called :handle-dead-message-ok)})
      (lhb/publish channel "message-exchange" qname "dummy payload" { :message-id (str (java.util.UUID/randomUUID))})
      (Thread/sleep (* 2 (+ 30 (* 30 30) (* 30 30 30))))
      (is (.await latch 700 TimeUnit/MILLISECONDS));;await causes the current thread to wait until the latch has counted down to zero, unless the thread is interrupted
      (is (.await latch-dead 700 TimeUnit/MILLISECONDS));;await causes the current thread to wait until the latch has counted down to zero, unless the thread is interrupted
      (is (= #{:handle-consume-ok :retry-0 :retry-1 :retry-2 :retry-3 :handle-dead-message-ok} @event-list)))))

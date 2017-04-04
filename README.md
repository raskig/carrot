# carrot: 

##

Library to keep your rabbits running...

![alt tag](https://cloud.githubusercontent.com/assets/3204818/23513284/5d24a108-ff5b-11e6-8f0d-12126f820385.png) 

A Clojure library designed to provide you with the implementation of the following simple RabbitMq delayed retry mechanism:
![alt tag](https://cloud.githubusercontent.com/assets/3204818/23512162/99eec068-ff57-11e6-9176-a883f79a9e22.png)


The idea is the following:

1. Provider sends a message to the message queue
2. Consumer tries to process it
3. While processing some exception is thrown
4. Failed message gets into the waiting-queue for a period of time which can be configured (ttl)
5. After the ttl expired the message is put back to message queue to try to process it again
6. Steps 3-5 repeated until the message successfully processed or until the number of retries are less than the max-retry you running carrot with.
7. When we exceed max retry, we put the message in the corresponding dead-letter queue sorted.

## Releases and Dependency Information

* I publish releases to [Clojars]

* Latest release is [carrot "1.0.0"]

* [All releases]

[Leiningen] dependency information:

   [carrot "1.0.0"]

## Usage

No need to worry if the above diagram seems to be too complicated. The idea is that you give custom names to the queues and exchanges you find on the diagram and Carrot will provide you with the retry mechanism and will create the architecturev for you.

Main steps:

- Require carrot in your code:

```clojure
:require [carrot.core :as carrot]
```
- Define your exchange and queue names in a carrot config map along with the retry configuration strategy (for details see [architecture](https://cloud.githubusercontent.com/assets/3204818/23512162/99eec068-ff57-11e6-9176-a883f79a9e22.png))

simple backoff example:

```clojure
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
```
exponential backoff example:

```clojure
(def carrot-system {:retry-config {:strategy :exp-backoff
                                         :initial-ttl 30
                                         :max-ttl 360000
                                         :max-retry-count 3
                                         :next-ttl-function exp-backoff-carrot/next-ttl}
                          :waiting-exchange "waiting-exchange"
                          :dead-letter-exchange "dead-letter-exchange"
                          :waiting-queue "waiting-queue"
                          :message-exchange "message-exchange"
                          :exchange-type "topic"
                          :exchange-config {:durable true}
                          :waiting-queue-config {:arguments {"x-max-length" 1000}}})
```

In case of exponential backoff, you can define your own function to determine the next ttl value after a retry.


- Declare your carrot system which will declare exchanges and queues with the given configuration:
```clojure
(carrot/declare-system channel carrot-system)
 ```
 Here the "channel" is the open langohr connection.

- You subscribe for your message queues by using carrot subscribe function:
```clojure
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
```
[Full example code](src/carrot/examples/example.clj)

## Exception handling
- You  can throw exceptions for not retrying. If you throw this exception it indicates that carrot should not retry the message because the message can't ever be processed, so no reason for retrial.

```clojure
(carrot/throw-do-not-retry-exception "Something fatal happaned." {:cause "The message can not be processed."})
```
[Example code for this](src/carrot/examples/example_with_no_retry_exception.clj)

- Optionally you  can declare functions you don't accept exceptions from, for retrying. Bacically you enlist functions here where any exception means the message can't ever be processed, so no reason for retrial.

```clojure
(carrot/do-not-retry! [#'my-namespace/message-handler-02
                #'my-namespace/message-handler-04])

```
[Example code for this](src/carrot/examples/example_without_retry.clj)

## Exponencial backoff
- You can configure carrot to run with exponencial backoff by defining te system using ex-backoff startegy: 

```clojure
(def carrot-system {:retry-config {:strategy :exp-backoff
                                         :initial-ttl 30
                                         :max-ttl 360000
                                         :max-retry-count 3
                                         :next-ttl-function exp-backoff-carrot/next-ttl}
                          :waiting-exchange "waiting-exchange"
                          :dead-letter-exchange "dead-letter-exchange"
                          :waiting-queue "waiting-queue"
                          :message-exchange "message-exchange"
                          :exchange-type "topic"
                          :exchange-config {:durable true}
                          :waiting-queue-config {:arguments {"x-max-length" 1000}}})
```
- You can defne your own "next-ttl" implementation instead of carrot's [reference implementation](https://github.com/raskig/carrot/blob/master/src/carrot/exp_backoff.clj#L8).




## TODOS
- support function for replaying messages ended up in dead letter queue
- possibly: provide a function to hanldle dead letters and one strategy could be to put them in dead letter queues

## License

Copyright © 2017 Gabor Raski

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

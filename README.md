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

* Latest snapshot release is [carrot "0.1.1-SNAPSHOT"]

* [All releases]

[Leiningen] dependency information:

   [carrot "0.1.1-SNAPSHOT"]

## Usage

No need to worry if the above diagram seems to be too complicated. The idea is that you give custom names to the queues and exchanges you find on the diagram and Carrot will provide you with the retry mechanism and will create the architecturev for you.

Main steps:

- require carrot in your code:

```clojure
:require [carrot.core :as carrot]
```
- define your exchange and queue names in a carrot config map (for details see [architecture](https://cloud.githubusercontent.com/assets/3204818/23512162/99eec068-ff57-11e6-9176-a883f79a9e22.png)):

```clojure
(def carrot-config {:waiting-exchange "waiting-exchange"
                    :dead-letter-exchange "dead-letter-exchange"
                    :waiting-queue "waiting-queue"
                    :message-exchange "message-exchange"})
```

- Declare your carrot system which will declare exchanges and queues with the given configuration:
```clojure
 (carrot/declare-system ch
                           carrot-config
                           3000;;ttl spent in waiting queue (in milliseconds)
                           "topic";;type for the exchanges
                           {:durable true};;config for the exchanges
                           )
 ```

- you subscribe for your message queues by using carrot subscribe function:
```clojure
(carrot/subscribe ch
                      carrot-config
                      qname
                      ;;use carrot to create the message handler for langohr:
                      (carrot/crate-message-handler-function
                       (carrot/compose-payload-handler-function
                        message-handler-01
                        message-handler-02
                        message-handler-03
                        ;;here you can en list more functions and they will be threaded in order via threading macro
                        ;;and will compose a message handler function
                        )
                       qname
                       3
                       carrot-config
                       logger)
                      {:auto-ack true})
```
- declare finctions you don't accept exceptions for retrying. Bacically you enlist functions here where any exception means the message can't ever be processed, so no reason for retrial

```clojure
(carrot/do-not-retry! [#'my-namespace/message-handler-02
                #'my-namespace/message-handler-04])

```

[Full xample code](src/carrot/examples/example.clj)

## License

Copyright © 2017 Gabor Raski

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

(defproject carrot "0.1.3"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                     :sign-releases false
                                     ;;:signing {:gpg-key "0xAB123456"}
                                    }]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;; rabbitmq client
                 [com.novemberain/langohr "3.6.1"]


                 ;; logging
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-api "1.7.21"]
                 [ch.qos.logback/logback-classic "1.1.7" :exclusions [org.slf4j/slf4j-api]]
                 [ch.qos.logback/logback-access "1.1.7"]
                 [ch.qos.logback/logback-core "1.1.7"]


                 ;; errors
                 [dire "0.5.4"]])

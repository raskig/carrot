(defproject carrot "0.1.1"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :deploy-repositories [["releases" {:url "http://blueant.com/archiva/internal/releases"
                                     ;; Select a GPG private key to use for
                                     ;; signing. (See "How to specify a user
                                     ;; ID" in GPG's manual.) GPG will
                                     ;; otherwise pick the first private key
                                     ;; it finds in your keyring.
                                     ;; Currently only works in :deploy-repositories
                                     ;; or as a top-level (global) setting.
                                     :signing {:gpg-key "0xAB123456"}}]
                        ["snapshots" "https://clojars.org/archiva/internal/snapshots"]]
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

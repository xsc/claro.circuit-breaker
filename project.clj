(defproject claro/circuit-breaker "0.1.1-SNAPSHOT"
  :description "Circuit-breaker middleware for claro execution engines."
  :url "https://github.com/xsc/claro.circuit-breaker"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"
            :author "Yannick Scherer"
            :year 2017
            :key "mit"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [claro "0.2.20" :scope "provided"]
                 [io.github.resilience4j/resilience4j-circuitbreaker "0.10.1"]]
  :profiles {:dev
             {:dependencies [[org.clojure/test.check "0.9.0"]
                             [ch.qos.logback/logback-classic "1.2.3"]
                             [org.slf4j/slf4j-api "1.7.25"]]}
             :codox
             {:dependencies [[org.clojure/tools.reader "1.1.0"]
                             [codox-theme-rdash "0.1.2"]]
              :plugins [[lein-codox "0.10.3"]]
              :codox {:project {:name "claro/circuit-breaker"}
                      :metadata {:doc/format :markdown}
                      :themes [:rdash]
                      :source-paths ["src"]
                      :source-uri "https://github.com/xsc/claro.circuit-breaker/blob/master/{filepath}#L{line}"
                      :namespaces [claro.middleware.circuit-breaker]}}
             :coverage
             {:plugins [[lein-cloverage "1.0.9"]]
              :dependencies [[org.clojure/tools.reader "1.1.0"]
                             [riddley "0.1.14"]]}}
  :aliases {"codox" ["with-profile" "codox,dev" "codox"]
            "cloverage" ["with-profile" "+coverage" "cloverage"]}
  :pedantic? :abort)

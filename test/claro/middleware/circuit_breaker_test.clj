(ns claro.middleware.circuit-breaker-test
  (:require [clojure.test.check
             [clojure-test :refer [defspec]]
             [generators :as gen]
             [properties :as prop]]
            [clojure.test :refer :all]
            [claro.data :as data]
            [claro.engine :as engine]
            [claro.middleware.circuit-breaker :as cb]))

;; ## Resolvable

(defrecord R [error?]
  data/Resolvable
  (resolve! [_ _]
    (if error?
      (throw (IllegalStateException.))
      ::success)))

(defrecord Pure []
  data/PureResolvable
  data/Resolvable
  (resolve! [_ _]
    ::pure))

(def Err (->R true))
(def Suc (->R false))

;; ## Generators

(def gen-cb-constructor
  (gen/elements
    [cb/circuit-breaker
     (fn [cfg name]
       (cb/circuit-breaker
         (cb/configuration cfg)
         name))
     (fn [cfg name]
       (cb/circuit-breaker
         (cb/registry (cb/configuration cfg))
         name))]))

(def gen-percentage
  (gen/large-integer* {:min 1, :max 100}))

(def gen-duration
  (gen/large-integer* {:min 1000, :max 10000}))

;; ## Helpers

(defn- warmup-circuit-breaker!
  [engine failures]
  (dotimes [_ (- 100 failures)]
    (is @(engine Suc)))
  (dotimes [_ failures]
    (is (thrown? IllegalStateException @(engine Err)))))

;; ## Tests

(defspec t-circuit-breaker-constructor 100
  (prop/for-all
    [make-cb gen-cb-constructor
     cfg (gen/one-of
           [(gen/elements [{} nil])
            (gen/hash-map
              :failure-rate-threshold              gen-percentage
              :wait-duration-in-open-state         gen-duration
              :ring-buffer-size-in-half-open-state gen-percentage
              :ring-buffer-size-in-closed-state    gen-percentage)])]
    (make-cb cfg "some-name")))

(defspec t-wrap-circuit-breaker* 100
  (prop/for-all
    [failure-rate-threshold gen-percentage
     failures               gen-percentage]
    (let [cb (cb/circuit-breaker
               {:failure-rate-threshold failure-rate-threshold}
               "Test")
          engine (-> (engine/engine)
                     (cb/wrap-circuit-breaker* (constantly cb)))]
      (warmup-circuit-breaker! engine failures)
      (let [followup-result @(engine Suc)]
        (if (< failures failure-rate-threshold)
          (= ::success followup-result)
          (and (data/error? followup-result)
               (= "circuit-breaker 'Test' is open."
                  (data/error-message followup-result))
               (= {:failure-rate        (double failures)
                   :not-permitted-calls 1}
                  (data/error-data followup-result))))))))

(defspec t-wrap-circuit-breaker*-heeds-predicate 100
  (prop/for-all
    [failure-rate-threshold gen-percentage
     failures               gen-percentage]
    (let [cb (cb/circuit-breaker
               {:failure-rate-threshold failure-rate-threshold}
               "Test")
          engine (-> (engine/engine)
                     (cb/wrap-circuit-breaker*
                       (fn [{:keys [disable?]} _]
                         (if-not disable?
                           cb))))]
      (warmup-circuit-breaker! engine failures)
      (= ::success @(engine Suc {:env {:disable? true}})))))

(defspec t-wrap-circuit-breaker*-ignores-pure-resolvables 100
  (prop/for-all
    [failure-rate-threshold gen-percentage
     failures               gen-percentage]
    (let [cb (cb/circuit-breaker
               {:failure-rate-threshold failure-rate-threshold}
               "Test")
          engine (-> (engine/engine)
                     (cb/wrap-circuit-breaker* (constantly cb)))]
      (warmup-circuit-breaker! engine failures)
      (= ::pure @(engine (->Pure))))))

(defspec t-wrap-circuit-breaker-heeds-throw-when-open? 100
  (prop/for-all
    [failure-rate-threshold gen-percentage
     failures               gen-percentage]
    (let [cb (cb/circuit-breaker
               {:failure-rate-threshold failure-rate-threshold}
               "Test")
          engine (-> (engine/engine)
                     (cb/wrap-circuit-breaker*
                       (constantly cb)
                       {:throw-when-open? true}))]
      (warmup-circuit-breaker! engine failures)
      (if (< failures failure-rate-threshold)
        (= ::success @(engine Suc))
        (boolean
          (is
            (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"circuit-breaker 'Test' is open."
              @(engine Suc))))))))

(defspec t-wrap-circuit-breaker 100
  (prop/for-all
    [failure-rate-threshold gen-percentage
     failures               gen-percentage]
    (let [engine
          (-> (engine/engine)
              (cb/wrap-circuit-breaker
                (constantly :default)
                {:default
                 {:failure-rate-threshold failure-rate-threshold}}))]
      (warmup-circuit-breaker! engine failures)
      (let [followup-result @(engine Suc)]
        (if (< failures failure-rate-threshold)
          (= ::success followup-result)
          (data/error? followup-result))))))

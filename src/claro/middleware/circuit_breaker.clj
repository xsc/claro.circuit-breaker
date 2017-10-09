(ns claro.middleware.circuit-breaker
  "Circuit-breaker middleware for [claro](https://github.com/xsc/claro) engines,
   based on [resilience4j](https://github.com/resilience4j/resilience4j#circuitbreaker)'s
   circuit-breaker implementation."
  (:require [claro.data :as data]
            [claro.engine :as engine]
            [claro.runtime.impl :as impl]
            [potemkin :refer [defprotocol+]])
  (:import [io.github.resilience4j.circuitbreaker
            CircuitBreaker
            CircuitBreakerConfig
            CircuitBreakerRegistry]
           [java.time Duration]))

;; ## Configuration

(defn configuration
  "Create a circuit-breaker configuration.

   - `failure-rate-threshold`: the percentage (between 1 and 100) of failed
     calls that cause the circuit-breaker to open,
   - `:wait-duration-in-open-state`: the time in milliseconds before an open
     circuit-breaker reverts to its half-open state,
   - `:ring-buffer-size-in-closed-state`: the number of calls that are taken
     into account when calculating the failure rate for a closed circuit
     breaker,
   - `:ring-buffer-size-in-half-open-state`: the number of calls that are taken
     into account when calculating the failure rate for a half-open circuit
     breaker,
   - `:record-failure-predicate`: a predicate applied to each observed exception
     deciding whether it should contribute to the failure rate.

   So, based on the default values, at least 50 of the last 100 calls have to
   have failed for the circuit-breaker to open. It will then stay open for 60s
   before transitioning into the half-open state, allowing 10 more calls. If
   5 or more of those calls failed, it will open back up and close otherwise."
  ^CircuitBreakerConfig
  [{:keys [failure-rate-threshold
           wait-duration-in-open-state
           ring-buffer-size-in-half-open-state
           ring-buffer-size-in-closed-state
           record-failure-predicate]
    :or {failure-rate-threshold              50
         wait-duration-in-open-state         60000
         ring-buffer-size-in-half-open-state 10
         ring-buffer-size-in-closed-state    100
         record-failure-predicate            (constantly true)}}]
  (let [predicate (reify java.util.function.Predicate
                    (test [this arg]
                      (boolean
                        (record-failure-predicate arg))))]
    (.. (CircuitBreakerConfig/custom)
        (failureRateThreshold failure-rate-threshold)
        (waitDurationInOpenState (Duration/ofMillis wait-duration-in-open-state))
        (ringBufferSizeInHalfOpenState ring-buffer-size-in-half-open-state)
        (ringBufferSizeInClosedState ring-buffer-size-in-closed-state)
        (recordFailure predicate)
        (build))))

(defn registry
  "Create a circuit-breaker registry. `options` are passed to [[configuration]]."
  ^CircuitBreakerRegistry
  [& [options]]
  (CircuitBreakerRegistry/of
    (configuration options)))

;; ## Constructor

(defprotocol+ ^:private CircuitBreakerConstructor
  (circuit-breaker
    [from name]
    "Create circuit-breaker from the given object, one of:

     - a [[configuration]] map,
     - a `CircuitBreakerRegistry`,
     - a `CircuitBreakerConfig`."))

(extend-protocol CircuitBreakerConstructor
  clojure.lang.IPersistentMap
  (circuit-breaker [options name]
    (circuit-breaker (registry options) name))

  nil
  (circuit-breaker [_ name]
    (circuit-breaker {} name))

  CircuitBreakerRegistry
  (circuit-breaker [registry name]
    (.circuitBreaker registry name))

  CircuitBreakerConfig
  (circuit-breaker [config name]
    (circuit-breaker
      (CircuitBreakerRegistry/of config)
      name)))

;; ## Resolution using CircuitBreaker

(defn- return-circuit-breaker-error
  [^CircuitBreaker cb]
  (let [metrics (.getMetrics cb)]
    (data/error
      (format "circuit-breaker '%s' is open." (.getName cb))
      {:failure-rate (.getFailureRate metrics)
       :not-permitted-calls (.getNumberOfNotPermittedCalls metrics)})))

(defn- throw-circuit-breaker-error
  [^CircuitBreaker cb]
  (let [e (return-circuit-breaker-error cb)]
    (throw
      (ex-info
        (data/error-message e)
        (data/error-data e)))))

(defn- resolve-with-circuit-breaker!
  [impl ^CircuitBreaker cb resolver env batch throw-when-open?]
  (if (.isCallPermitted cb)
    (let [start (System/nanoTime)]
      (letfn [(call-delta [f]
                (f (- (System/nanoTime) start)))
              (on-success [result]
                (call-delta #(.onSuccess cb %))
                result)
              (on-failure [^Throwable t]
                (call-delta #(.onError cb % t))
                (throw t))]
        (try
          (as-> (resolver env batch) <>
            (impl/chain impl <> on-success)
            (impl/catch impl <> on-failure))
          (catch Throwable t
            (on-failure t)))))
    (let [error (if throw-when-open?
                  (throw-circuit-breaker-error cb)
                  (return-circuit-breaker-error cb))]
      (->> (repeat error)
           (zipmap batch)
           (impl/value impl)))))

;; ## Middleware

(defn wrap-circuit-breaker*
  "Lower-level circuit-breaking middleware, wrapping resolution of batches
   using a circuit-breaker produced by `circuit-breaker-fn`.

   ```clojure
   (defonce engine
     (-> (engine/engine)
         (wrap-circuit-breaker*
           (fn [env [resolvable :as batch]]
             (when (instance? NeedsCircuitBreaking resolvable)
               some-circuit-breaker)))))
   ```

   `circuit-breaker-fn` will be called with the resolution environment and the
   batch to resolve and can return `nil` if no circuit-breaking should be done.

   If `throw-when-open?` is set, an `ExceptionInfo` will be thrown when a call
   reaches the open circuit-breaker; otherwise, a claro error value will be
   returned in place of the actual value.

   Note: The circuit breaker will never become active for `PureResolvable`
   values."
  [engine circuit-breaker-fn
   & [{:keys [throw-when-open?] :or {throw-when-open? false}}]]
  (let [impl (engine/impl engine)]
    (->> (fn [resolver]
           (fn [env [resolvable :as batch]]
             (if-not (data/pure-resolvable? resolvable)
               (if-let [circuit-breaker (circuit-breaker-fn env batch)]
                 (resolve-with-circuit-breaker!
                   impl
                   circuit-breaker
                   resolver
                   env
                   batch
                   throw-when-open?)
                 (resolver env batch))
               (resolver env batch))))
         (engine/wrap-pre-transform engine))))

(defn wrap-circuit-breaker
  "Circuit-breaking middleware that passes each batch to `dispatch-fn` to
   generate a value to-be-used to look up a circuit breaker instance in
   `circuit-breakers`.

   ```clojure
   (defonce engine
    (-> (engine/engine)
        (wrap-circuit-breaker
          (fn [env batch]
            (or (datasource-for (first batch))
                :default))
          {:database {:name \"Database\", :wait-duration-in-open-state 5000}
           :default  {:name \"Default\"}})))
   ```

   Any value that is accepted by [[circuit-breaker]] can be used in
   `circuit-breakers`."
  [engine dispatch-fn circuit-breakers & [options]]
  (let [circuit-breakers
        (->> (for [[value circuit-breaker-config] circuit-breakers
                   :let [cb-name (get circuit-breaker-config
                                      :name
                                      (pr-str value))
                         cb (circuit-breaker circuit-breaker-config cb-name)]]
               [value cb])
             (into {}))]
    (wrap-circuit-breaker*
      engine
      (fn [env batch]
        (let [cb-value (dispatch-fn env batch)]
          (get circuit-breakers cb-value)))
      options)))

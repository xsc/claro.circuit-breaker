# claro.circuit-breaker

This library provides a [claro][claro] middleware to add circuit-breaking to
resolution engines, using [resilience4j][res4j]'s circuit-breaker
implementation.

[![Build Status](https://travis-ci.org/xsc/claro.circuit-breaker.svg?branch=master)](https://travis-ci.org/xsc/claro.circuit-breaker)
[![Clojars Artifact](https://img.shields.io/clojars/v/claro/circuit-breaker.svg)](https://clojars.org/claro/circuit-breaker)
[![codecov](https://codecov.io/gh/xsc/claro.circuit-breaker/branch/master/graph/badge.svg)](https://codecov.io/gh/xsc/claro.circuit-breaker)

[claro]: https://github.com/xsc/claro
[res4j]: https://github.com/resilience4j/resilience4j#circuitbreaker

## Usage

```clojure
(require '[claro.engine :as engine]
         '[claro.middleware.circuit-breaker :as cb])
```

We can describe the different circuit-breakers by associating a name with
a circuit-breaker configuration:

```clojure
(def circuit-breakers
  {:db {:name                        "Database"
        :wait-duration-in-open-state 10000
        :record-failure-predicate    #(instance? IOException %)}})
```

Then we add the circuit-breaking middleware that returns a circuit-breaker
name to use (or `nil`) for each batch-to-resolve:

```clojure
(defonce engine
  (-> (engine/engine)
      (cb/wrap-circuit-breaker
        (fn [env batch]
          (if (uses-db? batch)
            :db))
        circuit-breakers)))
```

(`uses-db?` can be realised in a variety of ways, e.g. using a marker protocol
that is implemented by all resolvables that need DB access.)

Now, every `IOException` thrown by the targeted resolvables will contribute to
the circuit-breaker's failure rate. If it opens up, claro error values will
be returned in place of the actual results:

```clojure
@(engine {:result (->SomeResolvable)})
;; => {:result #<claro/error "circuit-breaker 'Database' is open."
;;                           {:failure-rate 60.0, :not-permitted-calls 2}>}
```

You can cause an exception to be thrown in these cases by passing
`:throw-when-open?` in `wrap-circuit-breaker`'s option map.

## License

```
MIT License

Copyright (c) 2017 Yannick Scherer

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

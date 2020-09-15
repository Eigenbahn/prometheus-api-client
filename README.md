# Prometheus HTTP API client for Clojure

Client library wrapper around [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/).

It provides the extraction of only relevant data from the responses and, for endpoints returning time series, automated response conversion to a more Clojure-friendly format.

As of writing, 100% of the endpoints are implemented, each corresponding to a function with extensive documentation.


## Installation

Add the following dependency to your `project.clj` file:

    [eigenbahn/prometheus-api-client "1.0.0"]


## General Usage

#### Connection

All methods take a connection `conn` as a first argument. It's just a simple map in the form:

```clojure
{:url "<prometheus_base_url>"}
```

The :url field value must contain the base URL of the Prometheus instance. It is not expected to contain the "/api/v1" part.

So to connect to a localhost instance:

```clojure
(def prom-conn {:url "http://localhost:9090"})
```

#### Time instants

Arguments corresponding to a time instant (and by extension the boundaries of a time range) can be in the following format:

- any valid Clojure `inst` object
- UNIX timestamp with second precision, with optional milliseconds after the comma, in string or number. E.g.: ``"1600093006"``, `1600093006`, `"1600093006.148"`, `1600093006.148`.
- an [RFC3339](https://www.ietf.org/rfc/rfc3339.txt) string. E.g. `1996-12-19T16:39:57-08:00`.


#### Optional arguments

All optional arguments are keyword arguments.


#### Error / Exception handling

Errors translate to HTTP error codes, throwing exceptions.

As we are relying on [clj-http.client](https://github.com/dakrone/clj-http), those exceptions are [Slingshot](https://github.com/scgilardi/slingshot) Stones. Refer to [this section](https://github.com/dakrone/clj-http#exceptions) to see how to handle them.


#### Result extraction & conversion

By default, only the most sensible data in the API response is returned by each function.

This can be tweaked by adjusting the value of dynamic var `content-level`:

- `::http-client`: raw value from `clj-http.client`, good for debugging
- `::body`: HTTP body parsing into a clojure data structure
- `::data`: `"data"` part of the prometheus response
- `::best`: only the most sensible data for each endpoint (default)

Likewise, we by default convert time series into more clojure-friendly data structures (maps) with timestamps converted to `inst` and used as keys and values parsed when scalar instead of just strings. This can be disabled by setting `convert-result` to false.


## Example usage

All the endpoints are implemented.
Only the TSDB querying-related ones are presented here.

#### Querying time series

```clojure
;; a single point at a given time (/query endpoint)
(prometheus-api.client/query prom-conn "scrape_duration_seconds" :at 1600093006.148)
;; -> [{"value" {#inst "2020-09-14T14:16:46.148000000-00:00" 0.004643278},
;;      "metric"
;;      {"job" "prometheus",
;;       "instance" "localhost:9090",
;;       "__name__" "scrape_duration_seconds"}}]

;; the last point
(prometheus-api.client/query prom-conn "scrape_duration_seconds")
;; -> [{"value" {#inst "2020-09-15T16:31:02.564000000-00:00" 0.005696021},
;;      "metric"
;;      {"job" "prometheus",
;;       "instance" "localhost:9090",
;;       "__name__" "scrape_duration_seconds"}}]

;; over a time range (/query-range endpoint)
(prometheus-api.client/query prom-conn "scrape_duration_seconds"
                             #inst "2020-09-14T12:16:04.148000000-00:00" ; from
                             #inst "2020-09-14T14:15:50.148000000-00:00" ; to
                             14)                                         ; step (in seconds unless unit specified)
;; -> [{"values"
;;      {#inst "2020-09-14T12:47:02.000000000-00:00" 0.00457012,
;;       #inst "2020-09-14T12:17:10.000000000-00:00" 0.00456644,
;;       ...
;;       #inst "2020-09-14T14:13:36.000000000-00:00" 0.004636848},
;;      "metric"
;;      {"job" "prometheus",
;;       "instance" "localhost:9090",
;;       "__name__" "scrape_duration_seconds"}}]
```


#### List labels & series

```clojure
;; list of label names (/labels endpoint)
(prometheus-api.client/labels prom-conn)
;; -> ["__name__" "branch" "call" ...]

;; list of values for a given label (/label/<label_name>/values endpoint)
(prometheus-api.client/values-for-label prom-conn "role")
;; -> ["endpoints" "ingress" "node" "pod" "service"]

;; list series by series matchers (/series endpoint)
(prometheus-api.client/series prom-conn ["up" "process_start_time_seconds{job=\"prometheus\"}"])
;; -> [{"job" "prometheus",
;;      "instance" "localhost:9090",
;;      "__name__" "process_start_time_seconds"}
;;     {"job" "prometheus", "instance" "localhost:9090", "__name__" "up"}]
```


## Similar projects & acknowledgement

Project inspired by the excellent [full-spectrum/influxdb-client](https://github.com/full-spectrum/influxdb-client) library for InfluxDB.

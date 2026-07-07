# Shipping Service

This repository contains the `shipping` service used in the enhanced Sock Shop
benchmark for the paper:

> EviRCA: An Evidence-Aware Skill-Based LLM Agent and a Telemetry-Rich
> Multi-Modal Benchmark for Microservice Root Cause Analysis

The service is derived from the original Sock Shop shipping component and has
been modernized for reproducible microservice root cause analysis (RCA)
experiments. In the Sock Shop workflow, it accepts shipping requests and
publishes shipment tasks to RabbitMQ for asynchronous processing by
`queue-master`.

## Role in the EviRCA Benchmark

The EviRCA benchmark enhances Sock Shop with synchronized metrics, logs, traces,
service topology, fault injection artifacts, upgraded service implementations,
and fine-grained labels. This repository is one of the upgraded Java services in
that benchmark.

For RCA data collection, this service provides:

- Spring Boot 3.4.1 and Java 17 runtime modernization.
- Spring Boot Actuator and Micrometer integration for health and Prometheus
  metrics.
- Brave-based distributed tracing through Micrometer Tracing and Zipkin export.
- RabbitMQ publish-side spans for asynchronous shipment messages.
- Kubernetes metadata tags on spans, including pod, namespace, node, and
  container.
- Trace-aware logs with `traceId` and `spanId` fields.
- Slow HTTP request logging for latency evidence.
- RabbitMQ health, recovery, timeout, DNS, connection, and publish failure logs.
- Chaos Monkey hooks for controlled controller/service-layer fault injection.

These changes make the service useful for multi-modal RCA because local
shipping faults and downstream RabbitMQ failures can be observed through metrics,
logs, and traces instead of only through user-facing symptoms.

## Service API

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/shipping` | Returns a simple shipping resource message. |
| `GET` | `/shipping/{id}` | Returns a simple shipping resource message for an id. |
| `POST` | `/shipping` | Accepts a shipment JSON body and publishes it to RabbitMQ queue `shipping-task`. |
| `GET` | `/health` | Returns application and RabbitMQ health status. |
| `GET` | `/metrics` | Exposes Prometheus metrics. |
| `GET` | `/actuator/chaosmonkey` | Exposes Chaos Monkey actuator controls when enabled. |

Example shipment request:

```bash
curl -X POST http://localhost:8080/shipping \
  -H 'Content-Type: application/json' \
  -d '{"id":"shipment-1","name":"demo"}'
```

## Observability

The service exposes Prometheus metrics at `/metrics`. In addition to Actuator
and JVM metrics, the HTTP interceptor records request latency as:

```text
request_duration_seconds{service,method,route,status_code}
```

Tracing is enabled by default and exports spans to Zipkin-compatible collectors:

```properties
management.tracing.enabled=${zipkin_enabled:true}
management.zipkin.tracing.endpoint=http://${zipkin_host:jaeger-collector.observability.svc.cluster.local}:9411/api/v2/spans
```

Health, metrics, Prometheus, and actuator endpoints are excluded from tracing to
reduce telemetry noise during benchmark collection.

Logs include explicit trace and span labels:

```text
[shipping,traceId:<trace-id>,spanId:<span-id>]
```

RabbitMQ failures are classified into evidence-friendly categories such as
`dns_failure`, `timeout`, `connection_refused`, `serialization`, and
`mq_publish_failure`.

## Configuration

Important runtime configuration is provided through environment variables or
Spring properties:

| Variable / property | Default | Purpose |
| --- | --- | --- |
| `port` | `8080` | HTTP server port. |
| `spring.rabbitmq.host` | `rabbitmq` | RabbitMQ host. |
| `zipkin_enabled` | `true` | Enables or disables tracing export. |
| `zipkin_host` | `jaeger-collector.observability.svc.cluster.local` | Zipkin-compatible collector host. |
| `HTTP_SLOW_REQUEST_THRESHOLD_MS` | `1000` | Slow request logging threshold. |
| `SHIPPING_RABBITMQ_CONNECTION_TIMEOUT_MS` | `4000` | RabbitMQ connection timeout used in logs/configuration. |
| `SHIPPING_RABBITMQ_WARMUP_ENABLED` | `true` | Enables RabbitMQ warm-up checks. |
| `SHIPPING_RABBITMQ_WARMUP_FAIL_FAST` | `false` | Fails startup when RabbitMQ warm-up fails if set to `true`. |
| `SPRING_PROFILES_ACTIVE` | `chaos-monkey` | Active Spring profile. |
| `POD_NAME`, `POD_NAMESPACE`, `NODE_NAME`, `CONTAINER_NAME` | fallback values | Kubernetes metadata attached to spans. |

## Build

```bash
mvn -DskipTests package
```

## Test

Run unit tests:

```bash
mvn test
```

Run integration tests:

```bash
mvn failsafe:integration-test failsafe:verify
```

The legacy test wrapper is still available:

```bash
./test/test.sh unit.py
./test/test.sh component.py
```

## Run Locally

Start the service with Maven:

```bash
mvn spring-boot:run
```

Check health:

```bash
curl http://localhost:8080/health
```

When running outside the full Sock Shop deployment, make sure RabbitMQ is
reachable at the configured `spring.rabbitmq.host`; otherwise `/health` reports
the RabbitMQ dependency as `err` while the application health entry remains
visible.

## Docker

Build an image with the existing build script:

```bash
GROUP=weaveworksdemos COMMIT=test ./scripts/build.sh
```

Push an image with:

```bash
GROUP=weaveworksdemos COMMIT=test ./scripts/push.sh
```

## Upstream Origin

The original Sock Shop project is available at
<https://github.com/microservices-demo/microservices-demo>. This repository keeps
the Sock Shop service boundary but updates the implementation for the EviRCA
telemetry-rich RCA benchmark.

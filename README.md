# taac-spring-boot-starter

Token-aware adaptive admission control as a Spring Boot starter. Drop it in
front of any blocking call (LLM inference, GPU work, external API) and the
gate adapts its concurrency to keep the backend out of trouble.

Originally extracted from a thesis on virtual-thread-based RAG backends; the
default `vegas` policy is the one evaluated there — a Vegas-inspired
delay-based controller with token-length normalisation and a heap-pressure
override, fronting a token-weighted SJF queue.

## Quick start

`build.gradle`:

```groovy
implementation 'io.github.chanseok:taac-spring-boot-starter:0.1.0'
```

`application.yml`:

```yaml
taac:
  admission:
    policy: vegas        # vegas | vegas-pure | response-time | standard-aimd | fixed | baseline
    max-concurrency: 30
    min-concurrency: 5   # must be >= taac.token.max-weight when policy=vegas
    timeout-ms: 30000
  token:
    max-weight: 3
```

Use it:

```java
@Service
class MyLlmService {

    private final AdmissionTemplate admission;
    private final ChatClient chat;

    String ask(String prompt) {
        return admission.execute(prompt, chat::call);
    }
}
```

`AdmissionTemplate` counts the input/output tokens, picks the weight, opens an
admission slot, records the response time back into the policy, releases the
slot — all in one call.

## Going under the hood

If `AdmissionTemplate.execute` doesn't fit your shape, talk to the controller
directly. The handle is `AutoCloseable`, so `try-with-resources` does the
release for you:

```java
try (var token = admission.acquire(weight)) {
    var resp = chat.call(prompt);
    token.recordCompletion(inputTokens, tokenCounter.count(resp));
}
```

## Architecture

```
                    AdmissionTemplate           (facade)
                          │
                          ▼
                   AdmissionController          (interface)
                          │  acquire() → AdmissionToken (AutoCloseable)
                          ▼
       PolicyDrivenAdmissionController          (the one impl)
              │            │             │
              ▼            ▼             ▼
       ConcurrencyPolicy  AdmissionGate  AdmissionListener   (all extension points)
       ─────────────────  ─────────────  ──────────────────
        TokenAwareVegas   DualQueueGate   MetricsListener
        PureVegas         SemaphoreGate   (your beans …)
        ResponseTime
        StandardAimd
        Fixed                              WeightStrategy
                                           ────────────────
                                           TokenWeightTracker
                                           (your bean …)
```

Every bean above is `@ConditionalOnMissingBean`. Drop your own bean of any of
these interfaces in your application context and the autoconfiguration steps
aside for it. Adding a new admission policy or a custom gate doesn't require
touching the library.

## Policies

| `taac.admission.policy` | Class                                 | Notes                                          |
| ----------------------- | ------------------------------------- | ---------------------------------------------- |
| `vegas` *(default)*     | `TokenAwareVegasPolicy`               | Thesis proposal — token-normalised RTT + heap. |
| `vegas-pure`            | `PureVegasPolicy`                     | Brakmo & Peterson 1995, no LLM adaptations.    |
| `response-time`         | `ResponseTimeBasedConcurrencyPolicy`  | Discrete RTT-ratio bands, heap-aware.          |
| `standard-aimd`         | `StandardAimdPolicy`                  | Classic TCP AIMD.                              |
| `fixed`                 | `FixedConcurrencyPolicy`              | Constant permit count.                         |
| `baseline`              | `NoOpAdmissionController`             | No admission control — for A/B comparison.     |

## Listening to events

Anything with `implements AdmissionListener` that you register as a bean joins
the dispatch alongside the library's metrics listener:

```java
@Component
class SlackAlert implements AdmissionListener {
    @Override public void onAcquireFailed(AdmissionEvent.AcquireFailed e) {
        slack.send(e.detail());
    }
}
```

Order with `@Order` if necessary.

## Configuration reference

| Property                                       | Default     | Description                                                                   |
| ---------------------------------------------- | ----------- | ----------------------------------------------------------------------------- |
| `taac.enabled`                                 | `true`      | Turns the starter off without removing the dependency.                        |
| `taac.admission.policy`                        | `vegas`     | Picks the active policy/gate combination.                                     |
| `taac.admission.max-concurrency`               | `30`        | Initial and upper-bound permit target.                                        |
| `taac.admission.min-concurrency`               | `5`         | Lower bound. Must be ≥ `taac.token.max-weight` under `vegas`.                 |
| `taac.admission.timeout-ms`                    | `30000`     | How long `acquire` waits before throwing.                                     |
| `taac.admission.eval-interval-ms`              | `50`        | Refresh period for the control plane.                                         |
| `taac.admission.fair`                          | `false`     | Semaphore fairness — trades throughput for FIFO ordering.                     |
| `taac.admission.dynamic-capacity-fail-fast`    | `true`      | Reject a request immediately when its weight exceeds the current target.      |
| `taac.admission.underflow-mode`                | `log-only`  | `strict` throws on release underflow; `log-only` warns and clamps.            |
| `taac.admission.scheduler-idle-park-ms`        | `50`        | DualQueueGate scheduler idle park.                                            |
| `taac.admission.fast-path-enabled`             | `true`      | CAS fast path for uncontended acquires.                                       |
| `taac.admission.threshold.{moderate,high,critical}` | `0.70 / 0.85 / 0.92` | Heap-usage band edges.                                     |
| `taac.token.default-avg`                       | `500`       | Seed value for the EMA token tracker.                                         |
| `taac.token.max-weight`                        | `3`         | Cap on per-request permit cost.                                               |

## Build

```sh
./gradlew :taac-spring-boot-starter:build
./gradlew :taac-sample-app:bootRun
```

```sh
curl 'http://localhost:8080/demo/ask?q=hello'
curl 'http://localhost:8080/demo/metrics'
```

## Requirements

- Java 21+ — uses records, sealed types, virtual threads
- Spring Boot 3.5+

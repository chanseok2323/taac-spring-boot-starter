# taac-spring-boot-starter

Token-aware adaptive admission control as a Spring Boot starter. Put it in
front of any blocking call — LLM inference, GPU work, external API — and the
gate adapts its concurrency so the backend doesn't tip over.

Originally extracted from a thesis on virtual-thread-based RAG backends. The
default `vegas` policy is the one evaluated there: Vegas-style delay-based
control with token-length normalisation and a heap-pressure override, sitting
in front of a token-weighted SJF queue.

## Quick start

`build.gradle`:

```groovy
implementation 'io.github.chanseok:taac-spring-boot-starter:0.1.0'
```

> The artifact isn't published yet — see the *Publishing* section if you want
> to consume this from another project today.

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

`AdmissionTemplate` counts the input/output tokens, picks the weight, opens
an admission slot, feeds the response time back into the policy, releases
the slot. If the template's shape doesn't fit your call site, talk to the
controller directly — the token is `AutoCloseable`:

```java
try (var token = admission.acquire(weight)) {
    var resp = chat.call(prompt);
    token.recordCompletion(inputTokens, tokenCounter.count(resp));
}
```

## Architecture

```
                       AdmissionTemplate
                              │
                              ▼
                       AdmissionController
                              │
                              │ acquire() → AdmissionToken (AutoCloseable)
                              ▼
                PolicyDrivenAdmissionController
                ┌─────────────┼──────────────┬──────────────┐
                ▼             ▼              ▼              ▼
         ConcurrencyPolicy  AdmissionGate  HeapMonitor   AdmissionListener
         ─────────────────  ─────────────  ───────────   ─────────────────
          TokenAwareVegas    DualQueueGate                MetricsListener
          PureVegas          SemaphoreGate                (your beans …)
          ResponseTime
          StandardAimd
          Fixed                                          WeightStrategy
                                                         ─────────────
                                                         TokenWeightTracker
                                                         (your bean …)
```

Every bean in this picture is `@ConditionalOnMissingBean`. Register your own
bean of the same type and the autoconfiguration steps aside for it. Adding a
new policy, gate, listener, or weight strategy doesn't require changes here.

## Policies

| `taac.admission.policy` | Class                                  | Notes                                          |
| ----------------------- | -------------------------------------- | ---------------------------------------------- |
| `vegas` *(default)*     | `TokenAwareVegasPolicy`                | Thesis proposal — token-normalised RTT + heap. |
| `vegas-pure`            | `PureVegasPolicy`                      | TCP Vegas (Brakmo & Peterson, 1995) baseline.  |
| `response-time`         | `ResponseTimeBasedConcurrencyPolicy`   | Discrete RTT bands with a drain boost.         |
| `standard-aimd`         | `StandardAimdPolicy`                   | Textbook TCP AIMD.                             |
| `fixed`                 | `FixedConcurrencyPolicy`               | Constant permit count.                         |
| `baseline`              | `NoOpAdmissionController`              | No admission control — for A/B comparison.     |

## Listening to events

Any `AdmissionListener` bean in the context joins the dispatch alongside the
library's metrics listener. Use `@Order` if you need a specific order.

```java
@Component
class SlackAlert implements AdmissionListener {
    @Override public void onAcquireFailed(AdmissionEvent.AcquireFailed e) {
        slack.send(e.detail());
    }
}
```

## Configuration reference

| Property                                            | Default       | What it does                                                                 |
| --------------------------------------------------- | ------------- | ---------------------------------------------------------------------------- |
| `taac.enabled`                                      | `true`        | Turn the starter off without removing the dependency.                        |
| `taac.admission.policy`                             | `vegas`       | Pick the active policy / gate pair.                                          |
| `taac.admission.max-concurrency`                    | `30`          | Initial and upper-bound permit target.                                       |
| `taac.admission.min-concurrency`                    | `5`           | Lower bound. Must be ≥ `taac.token.max-weight` under `vegas`.                |
| `taac.admission.timeout-ms`                         | `30000`       | How long `acquire` waits before throwing.                                    |
| `taac.admission.eval-interval-ms`                   | `50`          | Refresh period of the control plane.                                         |
| `taac.admission.fair`                               | `false`       | `Semaphore` fairness — trades throughput for FIFO ordering.                  |
| `taac.admission.dynamic-capacity-fail-fast`         | `true`        | Reject a request the moment its weight exceeds the current target.           |
| `taac.admission.underflow-mode`                     | `log-only`    | `strict` throws on release underflow; `log-only` clamps and warns.           |
| `taac.admission.scheduler-idle-park-ms`             | `50`          | DualQueue scheduler idle park.                                               |
| `taac.admission.fast-path-enabled`                  | `true`        | CAS fast path for uncontended acquires.                                      |
| `taac.admission.threshold.{moderate,high,critical}` | `0.70 / 0.85 / 0.92` | Heap band edges. Critical also triggers an immediate min target.    |
| `taac.token.default-avg`                            | `500`         | Seed for the EMA token tracker.                                              |
| `taac.token.max-weight`                             | `3`           | Cap on per-request permit cost.                                              |

## Build

```sh
./gradlew :taac-spring-boot-starter:build
./gradlew :taac-sample-app:bootRun
```

```sh
curl 'http://localhost:8080/demo/ask?q=hello'
curl 'http://localhost:8080/demo/metrics'
```

## Publishing

The coordinate above (`io.github.chanseok:taac-spring-boot-starter:0.1.0`)
matches the build's `group` / artifact / version — but the artifact isn't on
any repository yet. To consume it from another project, pick one of:

* **Local Maven** — add the `maven-publish` plugin and run
  `./gradlew :taac-spring-boot-starter:publishToMavenLocal`. Consumers add
  `mavenLocal()` to their repositories.
* **JitPack** — push a tag and depend on `com.github.chanseok2323:taac-spring-boot-starter:<tag>`.
* **Maven Central** — verify ownership of the `io.github.chanseok` group via
  the Sonatype process; once approved, the coordinate above works as printed.

## Requirements

- Java 21+ (records, sealed types, virtual threads)
- Spring Boot 3.5+

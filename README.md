# Programming Exercise — Resilient Web Client

**Time: 60 minutes.** Read Part 1 only. Do not scroll to Parts 2–4 until you have Part 1 working — in the real session the interviewer reveals them one at a time.

---

## Context

You are on a team that scores business transfers for risk. Our service calls two downstream services on every request:

- `risk-scoring` — returns a risk score for a transfer
- `sanctions-check` — screens the sender and recipient

Both are slow and occasionally unhealthy. When one of them starts failing, we keep hammering it, our threads pile up on timeouts, and transfers back up. We want to stop calling a downstream service for a while once it looks unhealthy.

You have been given a small skeleton. **Implement `WebClient.execute(Request)`.**

---

## Part 1 — Core requirements

1. `execute(Request)` routes the request to the named `RemoteService` and returns its `Response`.
2. If a service fails **3 times within a 10-minute window**, stop calling it: the next calls to that service are blocked for **5 minutes**.
3. A blocked call must **not** reach the downstream service. Throw `CircuitOpenException` instead.
4. After the 5 minutes have elapsed, calls to that service are allowed again.
5. Each service is tracked **independently** — `risk-scoring` being blocked must not affect `sanctions-check`.
6. A failure is a `RemoteServiceException` thrown by the service. It must still propagate to the caller.

> In the skeleton the durations are configurable and shrunk (window = 2s, open = 1.5s) so scenarios run fast. Do not hard-code them.

**Clarify before you code** (ask the interviewer — here, ask me):
- Is the 10-minute window sliding or fixed?
- Do only failures count toward tripping, or do successes reset the count?
- What should happen to in-flight calls?
- Is `execute` called concurrently?

Run the tests: see "How to run" below. Everything under `Part 1` must pass.

---

## Part 2 — Follow-up (reveal after Part 1 passes)

<details>
<summary>Open only after Part 1</summary>

The current behaviour sends full traffic at the service the instant the cooldown ends. If it is still down, we trip again immediately and every one of those callers eats a timeout.

Change it so that after the cooldown, **exactly one trial call** is allowed through (half-open):
- If the trial succeeds → the service is healthy again, the failure history is cleared.
- If the trial fails → blocked again for the full 5 minutes.
- Other calls arriving while the trial is in flight must be blocked, not queued.

</details>

---

## Part 3 — Follow-up

<details>
<summary>Open only after Part 2</summary>

`execute` is called from many request threads at once. Make the implementation thread-safe.

Be ready to explain:
- Where exactly the races are (check-then-act on state, failure counting, the half-open trial).
- Your choice: `synchronized` vs `ReentrantLock` vs atomics/`ConcurrentHashMap.compute`, and the trade-off.
- What happens under high load — lock contention, memory held by the failure history.

</details>

---

## Part 4 — Follow-up

<details>
<summary>Open only after Part 3</summary>

Instead of failing blocked calls, return the **last successful response** for that service if we have one, and only throw `CircuitOpenException` if we have nothing cached.

Then discuss (no code needed):
- How would this work if we ran 20 instances of this service? Where does the breaker state live?
- What would you emit to monitoring?
- How would you make the time-based logic unit-testable without `Thread.sleep`?

</details>

---

## How to run

Java 17+. Sources live in `src/main/java/`, tests in `src/test/java/`.

The JUnit suite is the acceptance check for this exercise:

```bash
cd simple-circuit-breaker
mvn test
```

It is grouped to match this brief — `Part 1` and `Part 2` map to the sections above — and it drives a hand-moved `Clock` rather than sleeping, so the whole suite runs in well under a second and never flakes on a loaded machine.

Useful variants:

```bash
mvn -q compile                       # just check that it compiles
mvn test -Dtest='WebClientTest$Part1'   # one part only
mvn clean test                       # start from a clean build
```

## Files

| File | Purpose |
|---|---|
| `WebClient.java` | **The only file you must change.** `execute` is unimplemented. |
| `RemoteService.java`, `FlakyRemoteService.java` | Simulated downstream. Tracks how many calls actually reached it. |
| `Request.java`, `Response.java` | Value types. |
| `RemoteServiceException.java`, `CircuitOpenException.java` | Failure signals. |
| `src/test/java/WebClientTest.java` | Acceptance tests, grouped by part. Read it if one fails, but do not weaken it. |
| `src/test/java/MutableClock.java` | Test clock, moved by hand instead of sleeping. |
| `src/test/java/ScriptedRemoteService.java` | Downstream double whose next outcome the test picks. |

You may add new classes/files — a separate breaker or failure-window type is a reasonable design.

---

## Self-review after the timer (do this before asking for feedback)

1. Did I clarify requirements before writing code, or assume?
2. Does the code read as a state machine, or as nested ifs?
3. Would a new requirement (a different trip rule) touch one class or many?
4. Did I name things after the domain, or after the data structure?
5. Did I run it, or just eyeball it?

## TODO
1. Handle http service 
2. Implement half open state
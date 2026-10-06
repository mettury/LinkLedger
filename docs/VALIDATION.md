# Validation record

The final clean build completed on 2026-10-06 at 19:33:41 UTC. All 44 default-suite cases and six real PostgreSQL cases passed, with zero failures, errors, or skips. Checkstyle reported zero violations. The evidence below separates these executed gates from unexecuted deployment and browser checks.

Results are scoped to the preserved source and execution artifacts. A configured CI job or a test file alone is not evidence that a check ran. This is AI-assisted validation; human review remains pending.

## Reproduce the main gates

From the project root, using a Java 21 JDK:

```bash
./mvnw clean verify
./mvnw -Pembedded-postgres clean verify
python3 scripts/security_check.py
node scripts/test_ui.cjs
```

For the optional OpenAPI schema gate, use an isolated Python environment and install the pinned validation-only dependencies:

```bash
python3 -m venv .venv-validation
# Activate that environment using your platform's normal activation command.
python -m pip install -r scripts/requirements-validation.txt
python scripts/validate_openapi.py
```

These Python dependencies are tooling, not application runtime requirements.

The embedded PostgreSQL profile requires non-root Linux x86_64. It launches a real PostgreSQL 17.6 process from test-only dependencies. On a machine with Docker, use `./mvnw -Ppostgres-tests verify` instead. Use only one PostgreSQL profile at a time.

For process-level checks, start the packaged application in a separate terminal, then run:

```bash
python3 scripts/demo.py
python3 scripts/load_smoke.py
```

These scripts default to `http://localhost:8080` and the documented local demonstration key. `BASE_URL` and `APP_API_KEY` can override those values. They inspect the shortener's redirect response and never follow destinations to third-party sites.

## Gate results

| Gate | Observed outcome | Evidence and qualification |
| --- | --- | --- |
| Final compile/package/tests | Passed, 44 default-suite cases | [Complete verification output](evidence/final-verify.log), [per-class reports](evidence/final-surefire/). Includes policy tests, H2-backed Spring integration, and real HTTP checks. |
| Real PostgreSQL 17.6 contracts | Passed, six cases | [Failsafe report](evidence/final-failsafe/), same final verification output. Native embedded profile; this was not an H2 simulation. |
| Checkstyle | Passed, zero violations | Final verification output. This is the configured rule set, not a comprehensive static security analyzer. |
| JaCoCo | Report generated | [Coverage summary](evidence/coverage-summary.json), [per-class data](evidence/coverage.csv). 93.6% line, 80.1% branch, and 93.2% instruction coverage. No coverage threshold or correctness guarantee is implied. |
| Live API demonstration | Passed | [API demo output](evidence/api-demo.log): create, replay, HEAD exclusion, destination, analytics, authentication, disable, and alias reservation. |
| Local concurrent redirect diagnostic | Passed, 200/200 `302` and 200 recorded counts | [Load result](evidence/load-smoke.json); workload and interpretation below. |
| Repository security guardrails | Passed | [Guardrail output](evidence/security-guardrails.log). Narrow secret-pattern and forbidden process/destination-fetch checks; not a dependency vulnerability scan or penetration test. |
| OpenAPI schema | Passed | [Schema validation output](evidence/openapi-validation.log), using `openapi-spec-validator` 0.7.2 against OpenAPI 3.0.3. Schema validity does not prove every runtime response conforms. |
| UI behavior simulation | Passed, 12 check groups | [Simulation output](evidence/ui-simulation.log), `scripts/test_ui.cjs`. Mock DOM and mocked API; does not establish visual layout or real-browser behavior. |
| Independent live review | Passed | [HTTP/restart results](evidence/independent-http.log): eight black-box groups plus input regressions, chunked-body rejection, and restart persistence. [Real storage-failure results](evidence/independent-storage-failure.log): failed counter storage preserves the redirect, failed authoritative storage returns `503`, and logs omit synthetic secrets. |
| Docker/Testcontainers and Compose topology | Not executed here | Configuration and tests are supplied. Native PostgreSQL success does not validate container building, networking, startup ordering, or volume handling. |
| Real-browser visual/accessibility review | Not executed | Browser execution was unavailable in the build environment. No screenshot or simulated DOM result is presented as a browser visual pass. |
| Remote CI and deployment | Not executed | CI configuration is supplied; no remote run or production deployment is claimed. |
| Human approval | Pending | Code review, product assumption approval, dependency/security assessment, and release approval require a real engineer. |

## What the tests exercise

| Test class | Cases in final run | Scope |
| --- | ---: | --- |
| `UrlPolicyTest` | 14 | Destination preservation, root/IPv6 handling, unsafe schemes, credentials, whitespace, bad ports, and malformed escaping. |
| `ApiIntegrationTest` | 4 | Spring API behavior, redirect and HEAD semantics, analytics, soft-disable, idempotency, and request errors including trailing JSON/extreme expiry. |
| `HttpSecurityTest` | 4 | Real HTTP authentication paths, known-length/chunked body limits, a valid create, and public resolution of an actuator-prefixed alias. |
| `RateLimitHttpTest` | 1 | Process-local creation limit behavior over real HTTP. |
| `LinkServiceTest` | 1 | Exact expiry boundary and replay after expiry with controlled time. |
| `CollisionTest` | 2 | Collision recovery and bounded exhaustion. |
| `AnalyticsFailureTest` | 1 | Injected repository failure preserves redirect, increments the failure metric, and leaves count unchanged. |
| `AliasTest` | 11 | Requested/generated identity, malformed/reserved aliases, case sensitivity, disabled alias reservation, and concurrent ownership. |
| `H2DatabaseTest` | 6 | Shared database contracts against H2. |
| `EmbeddedPostgresIT` | 6 | The same shared contracts against PostgreSQL 17.6. |

The shared database contracts exercise concurrent idempotency, independent repeated destinations, 100 concurrent counter writes with monotonic time, alias reservation and microsecond timestamp round-trip, concurrent alias claims, and successful Flyway migration history. They do not establish backup recovery, online migration safety, multi-region behavior, or load at production scale.

## Brownfield provenance

The pre-alias baseline completed its Maven test stage at 2026-10-06 19:25:27 UTC with 28 tests, no failures, no errors, and no skipped tests. It supports generated links, idempotency, expiry, analytics, authentication, body limits, and soft-disable; supplied custom aliases are rejected.

- [Baseline Maven output](evidence/baseline-test.log)
- [Baseline per-class reports](evidence/baseline-surefire/)
- [Preserved source snapshot](evidence/greenfield-baseline/)
- [Recorded local baseline commit](evidence/baseline-commit.txt): `9d4b98308f3f78ca3143042f7f284d4369d8acc5`, created at 19:26:11 UTC by the build process. This is neither human sign-off nor a published remote commit.
- [Pre-implementation alias test output](evidence/brownfield-red-test.log): 11 cases, one failure and two errors because positive/conflict behavior was not yet implemented. Eight input-rejection cases passed.

The final alias suite passes all 11 cases. The [scoped alias patch](evidence/brownfield-alias.patch) compares the affected source/test files with the baseline, including alias enablement and related hardening. A [companion review patch](evidence/review-hardening.patch) captures strict-JSON configuration and validation regressions. These patches are selected source comparisons, not a complete repository-wide diff. The [scenario report](SCENARIOS.md) identifies the alias-specific impact. Baseline success is not substituted for final verification.

## Local performance observation

`scripts/load_smoke.py` sent 200 GET requests to a single freshly created code with 10 client workers, on one local application/database instance. It did not follow redirects and had no dedicated warm-up phase. The correctness target was explicit: all 200 responses must be `302` and the resulting recorded count must be 200. Latency was observed, not evaluated against an invented production SLO.

| Observation | Result |
| --- | ---: |
| Requests / concurrency | 200 / 10 |
| Successful redirect responses | 200 |
| Recorded redirects | 200 |
| Elapsed time | 0.291 seconds |
| Observed request rate | 687.4 requests/second |
| Median latency | 13.06 ms |
| 95th percentile latency | 25.35 ms |
| Maximum latency | 39.61 ms |

The [raw result](evidence/load-smoke.json) is one short local sample. It uses a single hot code and synchronous counter writes, so it is useful for checking the implemented path and exercising contention. It does not establish sustainable capacity, tail latency under prolonged load, internet latency, resource-isolated performance, or resilience during database degradation. The [environment record](evidence/environment.json) identifies a shared Linux x86_64 workspace, Java 21.0.12.1, Spring Boot 3.5.16, and file-backed H2 2.3.232. The API demo ran immediately before this sample. The machine was not dedicated or resource-isolated, and CPU/memory allocations were not recorded; comparisons against other machines should therefore be treated cautiously.

## Independent review and observed defect closure

The [independent review report](INDEPENDENT_REVIEW.md) describes separately executed checks using a packaged application with disposable file-backed H2 data. The reviewed executable hash is recorded in [reviewed-jar.sha256](evidence/reviewed-jar.sha256):

`bb3094d11ae6b51e63eab61ac58efc467e585ce8a03005f19d75d4dab703eddf`

The first live review discovered two concrete input-validation defects: trailing JSON content could be accepted after a valid object, and an extreme expiry could fail during database conversion with a server error. After strict trailing-token rejection and an explicit expiry maximum were added, the same independent requests returned `400`. These are actual observed/fixed failures, not hypothetical risks.

The failure review also changed only its own disposable schema to make metrics unavailable. A resolved redirect still returned `302`, the failure metric incremented, and the statistics route returned a sanitized `503`. Removing authoritative link storage instead yielded `503` for resolution. A separate process restart retained the stored destination and count. This evidence exercises real JDBC failures and restart behavior beyond the mocked repository failure test.

The portable independent checks can be replayed without touching an existing database:

```bash
python3 scripts/independent_runner.py --jar target/linkledger-1.0.0.jar
python3 scripts/independent_failure_probe.py --jar target/linkledger-1.0.0.jar
```

Both scripts create their own disposable data. The failure probe obtains the H2 driver bundled inside the supplied application JAR. The [portable HTTP run](evidence/portable-independent-http.log) and [portable failure run](evidence/portable-independent-storage.log) also passed for the recorded JAR.

## How to interpret coverage and failures

Coverage shows which code was exercised; it does not show that assertions prove the right behavior. Some failure branches are harder to reach than happy paths, and aggregate percentages can hide them. Inspect the per-class report and the explicit failure tests rather than treating the total as a release gate by itself.

A deliberate red test run is preserved as change evidence and is clearly labeled. It is not a current failing gate. Conversely, Docker, browser, vulnerability scanning, and operational recovery work that did not run remains unverified even though the executed test suite is green.

## Source-distribution note

The reviewed executable JAR is not committed to this source repository. The recorded JAR hash identifies the original binary used for the preserved runtime checks, not an attached file or a promise of byte-for-byte reproducible rebuilds. Build the unchanged application source with the Maven wrapper to create `target/linkledger-1.0.0.jar`; build timestamps may change its hash. All recorded test results remain evidence from the original verified build.

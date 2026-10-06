# Independent acceptance and security review

Review date: 2026-10-06 UTC. Review type: independent AI-assisted source review and runtime verification, not human sign-off or a penetration-test certification.

## Result

No unresolved high- or medium-severity application defect was found within the reviewed scope after the corrections below. The final independent HTTP, concurrency, persistence, and dependency-failure checks passed. Production approval, dependency-vulnerability review, and deployment-specific validation remain open human responsibilities.

Reviewed executable JAR SHA-256:

`bb3094d11ae6b51e63eab61ac58efc467e585ce8a03005f19d75d4dab703eddf`

Runtime: Java 21.0.12.1, Spring Boot 3.5.16, embedded Tomcat, isolated file-backed H2. All test destinations were synthetic; redirect responses were inspected without visiting the destinations. Test mutations were confined to disposable databases. No project source, deployment, remote repository, or user database was changed by this review.

## Independently executed checks

The black-box suite exercised a packaged application through real HTTP, with its management API key and a separate disposable database:

- Unauthenticated create, metadata/analytics, and actuator access, including encoded characters, encoded/raw semicolons, double slashes, dot segments, and encoded slashes: no unauthorized successful management response.
- Redirect target preserves path case, escapes, repeated query values, semantic query parameters, and fragment. Host and forwarded headers cannot alter the configured short-link origin.
- HEAD returns the redirect without counting. GET counts once. Redirect responses disable caching. Ordinary repeated creation produces independent links.
- Unsafe schemes, relative destinations, credentials, invalid ports, whitespace/control injection, malformed JSON, unknown fields, oversized bodies, trailing JSON objects/garbage, and out-of-range expiry fail with the intended client error.
- Twelve concurrent alias claims produce exactly one creation and eleven conflicts; case-distinct aliases remain distinct. An `actuator`-prefixed valid alias redirects publicly.
- Twelve concurrent matching idempotency requests produce one creation and a single resulting code; a changed request using the same key conflicts.
- One hundred concurrent successful GET redirects result in exactly one hundred recorded redirects in this test.
- Expired and disabled links return 410 and do not increment. Expired aliases remain reserved. Matching idempotent replay after expiry returns the existing expired link. Unauthorized disable is denied and repeated authorized disable is idempotent.
- Unknown-length/chunked requests padded beyond 8192 bytes return 413.
- A real process stop/start with the same H2 file preserves the destination and counter; Flyway validates the existing migration on restart.

Controlled persistence failures were also tested against real JDBC, rather than only mocks:

1. Temporarily rename the metrics table while the disposable app is stopped, then restart. Resolution still returns 302; the analytics failure metric increments; the statistics API returns a sanitized 503.
2. Restore metrics, temporarily rename the authoritative links table, then restart. Resolution returns a sanitized 503 rather than inventing a destination.
3. Inspect failure logs: the synthetic destination query token and API key do not appear.

The repository's narrow secret/execution-pattern script and `git diff --check` were independently executed and passed. The secret script is not a comprehensive secret scanner or dependency vulnerability scan.

## Findings and closure

| Severity | Finding and evidence | Correction and verification |
|---|---|---|
| Medium | Oversized bodies could evade the original Content-Length-only check when streamed. Identified by source review before runtime verification. | `ApiAccessFilter` now bounded-reads and replays up to 8192 bytes. Real HTTP tests and independent chunked-whitespace probe return 413. |
| Medium | The original `/actuator` prefix match would protect valid aliases such as `actuatorPromo`. Identified by source inspection during alias development. | Protect the exact actuator root and its descendants. The independent authenticated-create/public-redirect case passes. |
| Low | Random generation could theoretically choose reserved route `actuator`. Source-level invariant gap; no naturally occurring collision was claimed. | Reserved candidates use the same bounded retry path. A deterministic generator test covers reserved and colliding candidates. |
| Medium | A valid JSON object followed by another object or literal garbage returned 201. Independently reproduced against the packaged app. | `application.yml` enables `fail-on-trailing-tokens`; both independent regression requests now return 400. |
| Low | `expiresAt` equal to `+1000000000-12-31T23:59:59.999999999Z` returned 500 because persistence conversion exceeded the timestamp representation. Independently reproduced. | `LinkService` validates a documented maximum of `9999-12-31T23:59:59.999999Z` before conversion; independent regression now returns 400. |

Relevant production paths are `src/main/java/dev/linkledger/security/ApiAccessFilter.java`, `src/main/java/dev/linkledger/links/LinkService.java`, `src/main/java/dev/linkledger/links/AliasPolicy.java`, and `src/main/resources/application.yml`.

A low-severity profile-isolation risk was identified: broad Failsafe discovery could include stale native-test classes when switching profiles without `clean`. The `postgres-tests` profile now explicitly includes only `PostgresIT`; the embedded profile includes only `EmbeddedPostgresIT`. Docker execution remains unverified.

## Source and engineering-evidence review

The implementation uses parameterized SQL, database uniqueness, atomic counter updates, fresh transaction boundaries for failed insert retries, injected time, and no destination-fetch path. Management and public redirect responsibilities are distinct. Best-effort analytics semantics and shared-key/abuse/scaling limits are candidly documented.

The preserved baseline source snapshot was inspected and verified to contain the actual pre-alias implementation, which rejects custom aliases. Baseline test reports record 28 passing tests. The current source implements a bounded custom-alias enhancement rather than a fictional pre-existing production change. Requirements and scenario documents distinguish assignment outcomes from proposed choices; no human approval or multi-day development history is asserted.

Current generated reports were inspected and showed 44 default tests and 6 native PostgreSQL 17.6 integration tests passing, with zero failures/errors/skips. Build-suite evidence and the separately executed black-box checks are distinct verification categories; both are preserved for review.

## Reproduction

The companion scripts need Python 3 and Java 21. Keep the runner and HTTP probe together. The runner copies the specified JAR into a new temporary directory, starts its own loopback instance, waits for health, executes the checks, verifies restart, and stops the process:

```bash
python3 scripts/independent_runner.py --jar target/linkledger-1.0.0.jar
python3 scripts/independent_failure_probe.py --jar target/linkledger-1.0.0.jar
```

Use `--java /path/to/java` or `--port 18089` when needed. The standalone `scripts/independent_http_probe.py --port PORT --key KEY` assumes an already running disposable application; do not point it at a real user's database. The failure runner creates its own database and never accepts an existing database URL.

Evidence logs: [independent HTTP log](evidence/independent-http.log) and [independent storage-failure log](evidence/independent-storage-failure.log). Both identify the reviewed JAR hash. The runner scripts included here also passed after their companion filenames were made bundle-relative; see the portable-independent logs. The failure runner extracts the bundled H2 driver into its temporary test directory when no explicit `--h2-jar` is provided.

## Limits and pending human review

- Docker Compose startup and the Docker-backed Testcontainers path were not independently executed in this environment. Native PostgreSQL passing does not validate container packaging/networking.
- This review did not run a comprehensive dependency/CVE scan, full penetration test, hostile-load/slow-client test, backup/restore exercise, disaster-recovery test, or long-duration performance test.
- The review did not independently validate real-browser rendering/accessibility; UI verification is a separate evidence category.
- Authentication is a shared key, not tenant isolation. Structural URL validation does not certify destinations as safe. Rate limiting is process-local. Best-effort synchronous counters can undercount or add latency under failure.
- Passing tests does not establish production readiness or constitute human engineering acceptance. Final human review and sign-off remain pending.

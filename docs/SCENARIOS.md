# Engineering scenarios

These three scenarios explain how a broad requirement becomes a bounded implementation, and how evidence distinguishes a plausible design from a working result. The brownfield scenario uses an actual earlier state of this project. It is not presented as a production incident, a pre-existing customer system, or a fictitious historical commit.

## Scenario 1 Build the core service from scratch

### Problem and acceptance

Create persistent short links, resolve active links without authentication, and expose protected metadata and basic request counts. Retain meaningful URL components. Keep behavior testable when time advances, identifiers collide, and writes fail.

The smallest useful vertical slice is a create request followed by a redirect and a statistics read. The slice is only acceptable if invalid destinations fail before persistence, missing and inactive links are distinguishable, and counters do not lose concurrent successful increments.

### Decomposition and execution

1. Establish the Java/Spring build and the two database configurations.
2. Define a migration with database-enforced code uniqueness, link lifetime, metrics, and idempotency records.
3. Implement `UrlPolicy` and the link model independently of HTTP.
4. Add parameterized JDBC repositories and transactional creation.
5. Add controller routes, the shared-key boundary, JSON validation, and structured errors.
6. Separate resolution from best-effort counter recording. Use an injected clock for lifetime checks and an atomic SQL expression for counters.
7. Exercise the entire API with Spring application integration tests, then add focused boundary and failure tests.

The baseline includes generated links, expiry, analytics, management authentication, idempotency, and soft-disable. A supplied custom alias is explicitly unavailable in that baseline. Capturing that difference provides a concrete enhancement boundary for the next scenario.

### What is validated

`UrlPolicyTest` covers path case, escaping, semantic query values, fragments, a root path, IPv6, forbidden schemes, credentials, whitespace, malformed input, and invalid ports. `ApiIntegrationTest` runs the Spring stack with a real test database and checks creation, redirect location, HEAD exclusion, analytics, disable, authentication, malformed JSON, unknown fields, and idempotency.

The final validation record identifies additional failure/concurrency tests, executed counts, and the database used. It also separates local integration tests from real PostgreSQL validation. Test source is evidence of intended checks; only completed run reports establish that those checks passed.

### Engineering judgment

A single database makes uniqueness, atomic creation, and durability straightforward to inspect. Separating link metadata and metrics avoids rewriting destination rows for every request. This is enough to expose the important failure boundaries before introducing a cache or event system with its own consistency and recovery problems.

## Scenario 2 Add custom aliases to a working baseline

### Change request

Allow a creator to choose a memorable short code while preserving the existing generated-code API and lifetime rules.

The invariant is stronger than checking whether an alias seems free: at most one link can ever own an identifier in the current retention model, including after expiry or disable. The database must arbitrate concurrent claims.

### Baseline and evidence

The real pre-alias source and its test outcome are preserved alongside the actual source/test diff. The [validation record](VALIDATION.md) lists the exact evidence files and result. There is no claim that a patch file is a signed commit or that a commit was created by a human.

The preserved [baseline source snapshot](evidence/greenfield-baseline/) corresponds to local build commit `9d4b98308f3f78ca3143042f7f284d4369d8acc5`. Its [test run](evidence/baseline-test.log) completed with 28 tests and no failures, errors, or skips before alias enablement. New alias tests were run before enablement: the [red-stage output](evidence/brownfield-red-test.log) shows 11 cases, with one failure and two errors caused by the unsupported feature. The [scoped source comparison](evidence/brownfield-alias.patch) records alias enablement, affected regression tests, and related hardening; the [companion review patch](evidence/review-hardening.patch) captures strict-JSON configuration and validation regressions. The final run passes all 11 alias cases, the 44-case default suite, and six real PostgreSQL contracts. Baseline success alone is not substituted for final validation.

### Impact analysis

| Area | Existing behavior | Required change or verification |
| --- | --- | --- |
| Request contract | Creation accepts the core URL and expiry fields; aliases are unavailable. | Enable the optional alias field without making it mandatory for old clients. |
| Policy/service | Generate a secure code and retry collision. | Validate alias syntax/reserved names, select it when supplied, and return conflict rather than choosing a replacement. |
| Persistence | `links.code` is unique. | Reuse the same constraint and namespace; avoid a second alias mapping that can diverge. |
| Transactions | Create link, metrics, and optional idempotency record atomically. | Preserve atomicity, reconcile concurrent replay, and never retry inside an aborted PostgreSQL transaction. |
| Redirects | Resolve a valid code and enforce lifetime. | Confirm both generated and custom identifiers use the same path and state checks. |
| Idempotency | Match a key to its request fingerprint. | Include alias identity so that a changed alias under one key is a conflict. |
| Existing clients | Omit `customAlias`. | Continue to receive generated links with the same response shape and status. |
| Documentation | Describe generated codes. | Explain alias syntax, reservation, conflicts, and no reuse after expiry/disable. |

The shared code column already accommodates the bounded alias length, so the enhancement should not require rewriting existing data or adding an alias table. That is an example of using the existing architecture rather than expanding it unnecessarily.

### Validation strategy

- New valid aliases resolve to the expected destination and are returned as requested.
- Malformed and reserved aliases fail with `400`.
- Duplicate claims return `409`; expired or disabled owners still reserve their aliases.
- Concurrent claims cannot produce two persisted owners.
- Omitting the field retains generated-code behavior.
- Matching idempotent retries return one link; changing the alias under the same key conflicts.
- The pre-existing end-to-end suite still passes after the enhancement.

The actual test names and observed outcomes belong to [validation](VALIDATION.md). A release of a real existing system would also need a staging migration check, rollback/roll-forward plan, deployed-version compatibility review, and engineer approval.

## Scenario 3 Make basic analytics precise

### Ambiguity

“Basic analytics” is interpreted here as a requirement category, not a complete metric definition. It leaves unanswered whether to count requests, successful page loads, unique people, campaigns, previews, bots, and retries. It also leaves accuracy, retention, latency, privacy, and failure priority unspecified.

### Clarification questions

1. Is the metric operational or financially consequential?
2. Does a click mean a redirect request, a client-observed redirect, or a destination page load?
3. Should HEAD, bots, repeated visits, or client retries count?
4. Which privacy-sensitive visitor data, if any, may be collected?
5. Should an analytics failure block a redirect?
6. How much lag or loss is acceptable, and what must be recoverable?

No product-owner answers were provided in the available brief. The prototype therefore makes its assumptions explicit instead of implying confirmation.

### Chosen contract

- A count attempt occurs for a GET that resolves to an active link.
- Repeats and bots are included. HEAD, nonexistent, expired, and disabled links are excluded.
- Counting is an atomic aggregate update, not a per-visitor event log.
- `lastAccessedAt` never moves backward after a later recorded timestamp.
- A recognized counter-write failure is tolerated after resolution and is observable through a failure metric.
- The service does not claim unique visitors, campaign attribution, confirmed page loads, exactly-once delivery, or recoverable lost increments.

### Decomposition and implementation

The policy is split into a controller decision, a counter service with a distinct transaction/failure boundary, and a repository expression with no application-side read-modify-write. This makes three different questions testable: should the request count, can a database write fail without killing the redirect, and do concurrent successful updates preserve the aggregate?

The metrics schema stores only code, total, and last-access timestamp. Choosing this model deliberately avoids the collection of IP addresses, device fingerprints, or other visitor attributes that the requirement did not justify.

### Validation and trade-off

The API test checks that HEAD leaves the count unchanged and a subsequent GET increments it. The focused failure test confirms that a redirect survives a counter failure and that the failure metric increments. Shared H2/PostgreSQL contracts verify 100 concurrent counter updates and a monotonic timestamp. The independent live review additionally makes the metrics table unavailable in a disposable database and observes `302` plus a failure signal, then makes authoritative storage unavailable and observes `503`. Exact outcomes and scope are in [validation](VALIDATION.md).

The trade-off is visible to clients and operators: reads remain useful when analytics cannot be written, but counts may be low and synchronous failure detection may add latency. If counts later become billable, the contract and architecture must change together. A durable outbox, event identifiers, consumer deduplication, and recovery testing would be candidates; simply adding a broker would not establish correctness.

## Common review standard

For each scenario, the reviewer should be able to follow a requirement to a design decision, a source path, a test, and an actual result. Where evidence is absent, the report calls it unverified. AI assistance can accelerate the sequence, but it does not provide human engineering approval or remove the need to understand the resulting system.

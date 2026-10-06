# LinkLedger architecture

LinkLedger is a modular Spring Boot application with a relational database. Its design keeps durable link identity in one authoritative store, makes redirect semantics explicit, and limits infrastructure so that the important correctness claims can be inspected and tested.

Java 21, Spring Boot, JDBC, Flyway, H2, PostgreSQL, JUnit, and Maven are declared in the source and build configuration. The exact dependency versions in `pom.xml` are authoritative. The implementation does not require Redis, Kafka, or a runtime language model.

## Components and boundaries

```text
API client or demonstration browser
    |
    | HTTPS at a deployment edge; HTTP only for local demonstration
    v
ApiAccessFilter
    request ID / security headers / shared-key check / create limit
    |
    v
LinkController
    HTTP validation and status / create / redirect / inspect / disable
    |                                      |
    v                                      v
LinkService                           AnalyticsService
    URL policy / identity / expiry        best-effort counter transaction
    collision and idempotency handling    aggregate success/failure metrics
    |                                      |
    v                                      v
LinkRepository                        AnalyticsRepository
    parameterized SQL                     atomic increment and max timestamp
    |                                      |
    +------------------+-------------------+
                       v
               H2 or PostgreSQL
               Flyway-managed schema
```

- `api` translates application outcomes into HTTP responses and structured error bodies. It does not generate codes or embed SQL.
- `links` owns URL validation, link state, random generation, alias rules, creation, and idempotency.
- `analytics` owns the aggregate count and the boundary that deliberately tolerates a counter-write failure.
- `security` provides a prototype shared-key access boundary and a process-local creation limit.
- `config` validates public-origin configuration and constrains JSON parsing.

These are in-process modules rather than independent services. Separating them is useful for testing and change impact analysis without introducing network calls between responsibilities.

## Browser demonstration client

The application serves a small same-origin HTML/CSS/JavaScript interface for creation, inspection, counting, and soft-disable. It calls the documented API directly and does not introduce a separate backend or user-account system. Request-derived values are rendered as text, outbound link schemes are checked, and the configured content-security policy restricts script loading to the same origin.

The page prefills only the public local demonstration key. A replacement key entered by a reviewer is held in the page state; the application does not write it to local storage or session storage. This is a convenience client, not a secure credential vault. Use a trusted browser and origin, and do not expose the local key as a real deployment credential. The client retains an idempotency key when an unchanged create request fails, so a retry can recover the server's original result.

## API surface

| Method and path | Access | Outcome |
| --- | --- | --- |
| `POST /api/v1/urls` | `X-API-Key` | Create a link from `url`, optional `customAlias`, and optional `expiresAt`; `201` for a new link, `200` for an idempotent replay. |
| `GET /{code}` | Public | `302` with the destination in `Location`; counts a valid active resolution on a best-effort basis. |
| `HEAD /{code}` | Public | Same resolution status and redirect location, without incrementing analytics. |
| `GET /api/v1/urls/{code}` | `X-API-Key` | Return metadata and current `ACTIVE`, `EXPIRED`, or `DISABLED` status. |
| `GET /api/v1/urls/{code}/analytics` | `X-API-Key` | Return `totalRedirects`, `lastAccessedAt`, and the measurement definition. |
| `DELETE /api/v1/urls/{code}` | `X-API-Key` | Soft-disable an existing link and return `204`; preserve its identity and analytics. |
| `GET /actuator/health` | Public | Health without internal component details. |
| `GET /actuator/metrics` and metric details | `X-API-Key` | Inspect operational metrics in a controlled evaluation environment. |

An unknown code returns `404`; an expired or disabled redirect returns `410`. Invalid creation input returns `400`, authentication failures `401`, alias or idempotency conflicts `409`, creation limiting `429`, and recognized storage failures `503`. The [OpenAPI contract](../src/main/resources/static/openapi.yaml) gives the exact schema and endpoint responses. Management response bodies may include private destination information and require the key even though the corresponding redirect is public.

## Link creation

1. Validate the request and destination before writing. Destinations must be absolute HTTP(S) URLs with a parseable host, no credentials, no raw whitespace/control characters, an acceptable port, and a bounded length.
2. Normalize scheme and host case, remove default HTTP(S) ports, and give an empty path a `/`. Preserve the destination path, raw query, and raw fragment, then produce an ASCII URI representation. This is limited normalization, not aggressive canonical deduplication.
3. Validate the optional idempotency key and compute hashes for the key and request. Check for an existing matching operation before allocating another link.
4. Validate expiry against an injected UTC clock. A supplied expiry must be in the future and no later than `9999-12-31T23:59:59.999999Z`; larger values are rejected before database conversion. New timestamps are reduced to microsecond precision to match database precision.
5. Allocate a requested alias or an eight-character secure random code. The generated alphabet is ambiguity-reduced and case-sensitive. Codes are opaque identifiers, not authorization secrets.
6. In one new transaction, insert the link, its zero-valued metrics row, and the optional idempotency record. Either all of these writes commit or none do.
7. On a database uniqueness conflict, leave the failed transaction and reconcile a concurrent idempotent request. Retry a generated-code collision in a fresh transaction, up to eight allocation attempts. A claimed custom alias is a conflict rather than an invitation to silently choose a different alias.
8. Return the persisted metadata. Build `shortUrl` from configured `APP_BASE_URL`, never from an untrusted request `Host` or forwarded header.

A failed statement can leave a PostgreSQL transaction unusable until rollback. The fresh-transaction retry boundary is therefore a correctness requirement, not just an implementation preference.

## Alias policy

Custom aliases are case-sensitive, 3–32 characters, and match `[A-Za-z0-9_-]{3,32}`. Omit `customAlias` to request a generated code; an empty string is invalid. The reserved names `api`, `actuator`, `error`, `assets`, `static`, `docs`, `index`, `favicon`, `robots`, `admin`, and `login` are rejected case-insensitively to preserve application routes. Aliases and generated codes use the same primary key. Expiry and soft-disable never free that key.

## Idempotency and identity

Ordinary create requests are independent even when they target the same destination. Supplying `Idempotency-Key` requests retry-safe creation instead. The database makes a key globally unique within this single shared-key service.

The stored key is a SHA-256 hash; the request fingerprint includes the validated destination, alias, and supplied expiry. Matching replay returns the existing link with `Idempotency-Replayed: true` and `200`. A differing request using the same key returns `409`. Hashing the request fields uses length-delimited parts to avoid ambiguous concatenation.

A replay can return an already expired or disabled link. It does not extend the lifetime or allocate a replacement. Idempotency records currently have no automatic retention or cleanup policy. A multi-tenant design would require a tenant-scoped key and a carefully chosen retention contract.

## Redirects and analytics

The controller first loads the authoritative link and checks its state. A GET for an active link attempts a counter update in a separate transaction, then returns `302`. HEAD skips the update. Both use `Cache-Control: no-store` so that compliant intermediaries do not retain mappings past expiry or bypass normal counting.

The update performs `total_redirects = total_redirects + 1` in SQL. It does not read a count into Java and then write a replacement. Concurrent accepted writes therefore do not overwrite one another. `last_accessed_at` keeps the greater timestamp so that out-of-order completion does not move it backward.

Analytics are synchronous and best-effort. Recognized database/transaction failures at the counter boundary produce an aggregate failure metric and a sanitized log entry; the already-resolved redirect continues. No durable event queue exists, so there is no later replay to recover a lost increment. A connection or query wait can still add latency before this failure is detected. The configured pool, query, and transaction timeouts constrain parts of the operation; they are not a proved end-to-end latency guarantee.

A recorded GET is not proof that the client received the response or loaded the target page. Bots, repeat requests, speculative requests, and client retries may count. Unknown, expired, disabled, and HEAD requests do not count. No IP address, user agent, cookie, or unique-visitor identifier is stored by the analytics model.

## Schema and invariants

Flyway migration `V1__create_links.sql` defines three tables:

| Table | Key | Purpose and invariant |
| --- | --- | --- |
| `links` | `code` primary key | Destination and lifetime. Non-null destination and creation time; expiry, when present, must follow creation time; soft-disable keeps the key occupied. |
| `link_metrics` | `code` primary and foreign key | One aggregate row per link, with a nonnegative count and optional last-access timestamp. |
| `idempotency_records` | `key_hash` primary key | Request fingerprint and resulting link; foreign key prevents a dangling reference. |

Code allocation and alias ownership use the same primary-key namespace. An application-side “is this free?” check cannot provide the same guarantee under concurrency. Expiration is evaluated during reads; there is no scheduler that deletes expired links. This preserves old identities and avoids a cleanup process becoming a correctness dependency.

Metrics use a separate row from link metadata so that counting does not update the destination row. A sufficiently hot link still serializes updates on one metrics row. The separation reduces unnecessary contention but does not make the counter infinitely scalable.

## Local and PostgreSQL modes

The default local profile uses a file-backed H2 database under `data/`, so restarting the process with the same working directory preserves data. H2 is convenient for a clean demonstration and fast tests; PostgreSQL compatibility mode does not establish full equivalence with PostgreSQL.

The PostgreSQL profile uses supplied connection settings and API key. Docker Compose provides the repeatable application/database path. Two test-only routes run the shared database contracts against real PostgreSQL 17.6: the `postgres-tests` profile uses Testcontainers and Docker, while `embedded-postgres` starts native PostgreSQL binaries on non-root Linux x86_64. Use one profile at a time. These are separate from H2 checks because real transaction-abort, uniqueness, timestamp, and locking behavior matter. The execution status of each route is recorded in [validation](VALIDATION.md).

Local configuration binds the application to loopback by default and includes a clearly identified demonstration key. That key is not suitable for exposure beyond a developer machine. A real deployment needs TLS, managed secrets, network restrictions, backups, a recovery procedure, and an appropriately scoped identity model.

## Failure model

| Failure or race | Intended behavior | Remaining limitation |
| --- | --- | --- |
| Random-code collision | Roll back and retry a fresh candidate, bounded to eight attempts. | Exhaustion is `503`; a broken generator is not concealed by an infinite loop. |
| Two requests claim one alias | Database primary key admits at most one owner; the other gets a conflict. | There is no alias transfer or release operation. |
| Concurrent matching idempotency keys | Reconcile against the committed record and return one link. | Keys are shared across the single service identity and retained indefinitely. |
| Database unavailable during lookup/create | Return a storage failure; do not invent a successful redirect or creation. | The database remains an availability dependency. |
| Counter write fails after resolution | Redirect still succeeds; increment a failure metric. | Undercount and added wait time are possible. |
| Disable races with resolution | Requests that already resolved may finish their redirect. | Soft-disable does not recall an in-flight response; strict revocation would need a stronger contract. |
| Expiry occurs during a request | Eligibility is checked at resolution time. | A response can arrive after expiry if it was resolved just before the boundary. |
| Application restarts | Links remain in the persistent database. | In-memory rate-limit state and process-local metrics restart. |
| Very hot code | Atomic counter remains correct for successful writes. | Row-lock contention and write load can increase latency. |

## Security boundaries and deployment gaps

The shared API key is compared without ordinary string early-exit comparison. All management routes require it. Prepared SQL avoids concatenating user input into queries. Unknown JSON fields and trailing JSON tokens are rejected, and parsing has string, nesting, and document limits. Create requests have an 8192-byte body limit, enforced by both a content-length check and bounded body reading for streamed requests. A deployment edge should also constrain slow uploads, connections, and request rates; a byte limit alone does not address slow-client resource exhaustion.

The application intentionally never fetches a destination. That removes a server-side destination-fetch SSRF path, but it does not stop users being redirected to malicious or internal destinations in their own browsers. A publicly offered shortener requires abuse controls and destination policy beyond structural URL validation. There is no threat-intelligence integration, domain reputation check, ownership verification, or takedown workflow.

The create limit is one fixed window shared by the current process and configured key. It is not per-user fairness, distributed rate limiting, redirect flood protection, or DDoS defense. Its counter resets on restart and can permit boundary bursts. Horizontal replication must not be described as preserving a global limit.

Only sanitized failure categories and request IDs should be logged. Avoid logging request headers, destination URLs, SQL parameter values, or raw exception bodies in infrastructure added around this service. Query strings can contain secrets even when a URL is otherwise valid. Public codes can be guessed or leaked; the shortener must never be used as an access-control layer for confidential resources.

## Scaling choices to revisit after measurement

Start with observed traffic shape and a real SLO. The next changes are conditional:

- Add a cache for read-heavy resolution only after defining invalidation for disable and expiry. Bound cache lifetime by the link expiry and decide the stale-link risk during an outage.
- Replace synchronous counter updates with a durable outbox/event pipeline when measured write contention justifies it. Define delivery, deduplication, lag, retention, and replay semantics before naming a broker.
- Partition or aggregate hot counters when a single metrics row becomes a bottleneck. Decide how much freshness and exactness can be traded for throughput.
- Add real tenant identity, tenant-scoped quotas and idempotency, key rotation, and authorization before offering management to multiple unrelated users.
- Add PostgreSQL backup/restore exercises, migration rollback/roll-forward practice, observability dashboards, and capacity tests before a production release.

These are design alternatives, not implemented capabilities or promises that adding infrastructure automatically improves reliability.

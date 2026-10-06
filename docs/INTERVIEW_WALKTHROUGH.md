# Interview walkthrough

Use this guide after reading the source and reproducing the demonstration. It explains the design rather than supplying a fictional first-person account. State clearly that AI generated and revised the implementation, and describe only the review and validation that you personally completed.

## Suggested demonstration order

### First two minutes Explain the scope

Open the root README and the architecture diagram. Describe LinkLedger as a backend-first shortener with persistent identity, explicit expiry, protected management, public redirects, and best-effort request counts.

Explain the main simplification: one application and one relational database make the transactional guarantees inspectable. Caches and brokers are deferred until a measured workload and a precise consistency contract justify them.

### Next five minutes Run the vertical slice

Use the README's supported startup and smoke commands. Use synthetic destinations and a demonstration key.

1. Create a destination containing a meaningful query parameter and a fragment.
2. Inspect the returned code, URL, timestamps, and state.
3. Make a HEAD request and show that analytics remain unchanged.
4. Make a GET without following the destination, verify the `302` and preserved `Location`, and inspect the count.
5. Demonstrate an idempotent replay and a conflicting change under the same key.
6. Create a custom alias, then show a duplicate claim is rejected.
7. Soft-disable a link and show the public redirect becomes `410` while metadata and historical analytics remain inspectable.
8. Show that a management request without a key is denied.

Avoid using a browser refresh as an exact counting test: browsers, previews, and developer tools can issue additional requests. A script or `curl` without automatic redirect following is easier to interpret.

### Next five minutes Show the important code

- `UrlPolicy`: structural validation and narrow normalization.
- `LinkService.create`: atomic creation, replay, collision handling, and transaction boundaries.
- `LinkService.resolve` and `Link.status`: missing, expired, and disabled behavior with an injected clock.
- `AnalyticsRepository.increment`: atomic increment and monotonic timestamp.
- `AnalyticsService.record`: the intentional fail-soft boundary.
- `ApiAccessFilter`: shared-key protection, request size/creation controls, and the limits of that model.

Explain one invariant per file instead of reading every line. Use the corresponding tests to establish what the code actually promises.

### Next five minutes Show change and evidence

Walk through the real pre-alias baseline and its diff. Identify the existing request path, service decision, namespace constraint, and compatibility tests affected by the enhancement. Explain why no new alias table was necessary.

Open the validation record. Distinguish compilation, static checks, unit tests, Spring/database integration tests, real PostgreSQL tests, smoke checks, and local performance measurements. Name any skipped or unexecuted stage directly.

### Final three minutes Discuss limits

Highlight the synchronous hot-row counter, possible undercount, single shared key, process-local limit, absence of public-abuse controls, and required production database/operational work. End with the next experiment you would run for the intended workload, rather than a generic claim that the service is ready for any scale.

## Questions and explanations

### Why is the short code a path variable and the destination a request body field

In `GET /api/v1/urls/launch-demo`, the `launch-demo` segment identifies the link being inspected. `LinkController.details(@PathVariable String code)` binds that route segment to the Java argument. The public `GET /launch-demo` route uses the same binding to identify the link being resolved.

Creation submits a structured resource with several related values: `url`, optional `customAlias`, and optional `expiresAt`. `@RequestBody CreateLinkRequest` binds that JSON body, and `@Valid` triggers its validation constraints. Putting the full destination in the body also avoids making it an additional query value that needs another layer of URL encoding. `X-API-Key` and `Idempotency-Key` are request headers, not path variables or query parameters.

The delivered controller has no query-parameter inputs. A destination such as `https://example.com/search?q=spring#results` contains a query belonging to the destination site. Here it is part of the JSON `url` value, stored and returned in the redirect `Location`; it does not become a query parameter of the LinkLedger management API.

### What is the difference between Spring RequestParam and QueryParam

Spring MVC uses `@RequestParam` for a controller argument taken from request parameters, commonly a URL query string or form field. `@PathVariable` reads a named route segment, and `@RequestBody` reads/deserializes the body. These annotations express different input locations.

`@QueryParam` belongs to Jakarta REST, also known as JAX-RS, rather than Spring MVC. It is the query-binding annotation used in that separate programming model. This project uses Spring MVC and does not use `@QueryParam` or `@RequestParam`; its current inputs are path variables, JSON bodies, and headers. Do not invent an extra search/filter route when explaining the code.

### Why use random codes instead of database IDs or a distributed ID generator

Random codes avoid exposing a simple sequential allocation order and do not require a globally coordinated counter. The generator uses `SecureRandom` and an eight-character, 57-symbol alphabet. The database remains the final uniqueness authority, and bounded retries handle collisions.

There are 57^8 possible generated codes, roughly 1.11 × 10^14. The probability of any collision across many allocations rises with the birthday effect; that is why “large space” does not replace a unique constraint. For a new allocation with `n` occupied generated values and a uniform generator, the approximate collision probability is `n / 57^8`. Custom aliases occupy some of the same namespace. None of this makes a short code an authorization credential.

### Why not shorten the same URL to the same code every time

Different creators or campaigns may need separate lifetimes and counters. Global URL deduplication introduces an ownership and attribution decision that the brief did not settle. Independent creation has a clear contract; an idempotency key gives clients an explicit way to retry one operation without producing multiple links.

### Why preserve queries and fragments

A query might select a product, authenticate a request, or change a search. A fragment might select a document section or client-side route. Removing either can change the destination. The policy only normalizes scheme/host case, default ports, and an empty root path; it preserves meaningful components and tests that behavior.

Fragments are not sent to this service when a client follows the destination, but they still matter in the `Location` URL delivered to the client.

### Does validating a URL solve SSRF or phishing

The application never fetches the destination, so it does not expose that server-side fetch route to SSRF. Structural validation also rejects non-HTTP(S) schemes and credentials. It does not prove that a site is safe, and clients can still be redirected to internal or malicious destinations. Internet exposure would require a separate abuse and destination-policy design.

### How are collisions safe under concurrency

The database primary key allows only one owner. An application lookup followed by an insert would race. Creation tries the insert transactionally, rolls back on conflict, reconciles idempotency when applicable, and uses a fresh transaction for another generated candidate. A custom-alias conflict is returned to the caller; choosing a different alias would violate the request.

### Why are retry transactions separate

A constraint violation can abort a PostgreSQL transaction. Continuing to issue statements inside that same failed transaction will not repair it. The retry boundary must include rollback and a new transaction. The test suite should exercise real PostgreSQL as well as the local database before relying on this across deployments.

### What is atomic about creation

The link, zero-valued metrics row, and optional idempotency record are inserted together. A transaction failure must not leave a link with no metrics row or an idempotency record pointing to an uncommitted link. Atomicity is enforced in the service transaction and supported by primary/foreign-key constraints.

### What happens if the client times out after creation committed

With a stable idempotency key, a retry of the same request retrieves the existing result. If the caller reuses the key for different content, it receives `409`. Without a key, a retry is a new creation and can make a second link. This is a client-visible contract, not a claim that networks provide exactly-once requests.

### Can an idempotent replay extend a link's lifetime

No. It refers to the original operation. An expired or disabled link can be returned as the replay result with its current state. Extending its life or replacing it would break the operation's meaning and, potentially, alias ownership. Key retention is currently unbounded and needs a product/operational policy at scale.

### Why use 302 and no-store rather than 301

A permanent cacheable redirect can cause a browser or intermediary to stop consulting the service. That interferes with lifetime checks, disabling, and request accounting. `302` with `no-store` makes the intended behavior temporary and discourages retention. It still cannot control malicious or noncompliant clients.

### What does the click count actually mean

It counts recorded GETs that resolved to an active link. Repeats and bots can count; HEAD, missing, expired, and disabled links do not. It does not measure unique people, successful destination loads, or receipt of the redirect response. Counter-write failures can undercount, and client retries can increase the count.

### How does the counter avoid lost updates

The database executes `total_redirects = total_redirects + 1` atomically. Java never reads a value and writes `old + 1` as a separate operation. The last-access timestamp uses the maximum of the previous and new timestamp to preserve monotonicity under out-of-order requests.

### Why not make analytics asynchronous immediately

A background thread alone would risk losing in-memory work and complicate shutdown. A durable event design needs an outbox or equivalent handoff, delivery guarantees, deduplication, lag handling, retention, monitoring, and replay. The synchronous aggregate is simple enough to validate. Its hot-row and latency limits are explicit, so measurement can justify the next design.

### What happens when the database goes down

If lookup fails, the service cannot establish a destination and returns a storage failure. If a destination was already resolved and only the analytics write fails, the redirect proceeds after failure handling. Both depend on where the failure occurs; “fail-soft analytics” does not make the entire service database-independent.

### Does a one-second transaction timeout guarantee a one-second redirect

No. Connection acquisition, statement execution, server scheduling, and response handling have separate costs and timeout behavior. The implementation sets bounded waits for individual database operations, but a strict end-to-end deadline requires dedicated design and measurement. The local benchmark is not that proof.

### What if expiry or disabling happens during a redirect

Eligibility is checked during resolution. A request that already passed the check can finish after the expiry instant or after another request disables the link. Strict revocation of in-flight responses is a different, more expensive contract. Tests establish the defined boundary, not a guarantee that previously emitted redirects can be recalled.

### Why keep expired and disabled aliases reserved

A reused code could send an old bookmark, QR code, or shared message to a different owner. Retaining identity prevents that class of accidental takeover. The cost is growing storage and namespace reservation. Any future cleanup must separate payload retention from the need to remember permanently reserved identities.

### Is the shared API key sufficient security

It protects the local management surface, but it does not model distinct users, ownership, roles, or tenant isolation. Everyone holding the key has the same management authority. Real exposure needs TLS, managed and rotatable credentials, scoped identity, audit policy, quotas, and abuse controls. The local demonstration key must never be treated as a deployment secret.

### Is the rate limit distributed

No. It is a fixed-window counter in one process, shared across callers using the configured key. It can reset on restart, multiply with replicas, and burst around a window boundary. A deployment-wide limit would require a gateway or shared design with explicit availability and fairness behavior.

### Why have H2 and PostgreSQL

H2 enables a runnable local example without requiring Docker, while PostgreSQL is the deployment-oriented persistence option. H2 compatibility mode cannot reproduce all PostgreSQL transaction, locking, SQL, or timestamp behavior. The real PostgreSQL integration gate remains separate. The project can run it through Docker/Testcontainers or native test binaries on non-root Linux x86_64; the actual execution status of each route must be stated honestly.

### What makes the brownfield example genuine

There is a preserved working source state that rejects custom aliases, followed by an actual enhancement and compatibility tests. The diff shows exactly which modules changed. It is a controlled evolution of this project, not evidence of maintaining an unrelated legacy production system or a fabricated months-long commit history.

### What did AI contribute and what does the engineer still own

AI generated and revised code, tests, architecture, and documentation, and assisted with execution and review. The engineer must inspect those artifacts, reproduce meaningful checks, assess the assumptions, make consequential decisions, and approve any release. Only claim the personal review you have actually performed. The AI workflow record identifies retained decisions and corrections without asserting human approval.

### What would you improve first for a real launch

Start from the intended users and workload. For public exposure, identity and abuse controls may precede performance work. For a trusted high-volume service, measure the database lookup/counter path, hot-link contention, and failure latency; define an SLO and durability goal; then choose caching or a durable analytics pipeline if the data warrants it. Backup recovery, observability, dependency/security gates, and PostgreSQL validation are release requirements, not optional polish.

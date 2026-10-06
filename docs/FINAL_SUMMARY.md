# Final engineering summary

LinkLedger implements a reviewable URL shortener with explicit behavior for identity, expiry, retries, counters, and failure. It uses a single Java/Spring application and a relational database to keep the important correctness boundaries small enough to inspect. The deliverable was generated and revised with AI assistance; human engineering review and release approval remain pending.

## Delivered design

- Persistent generated links and custom aliases in one database-enforced namespace.
- Public temporary redirects with preserved destination path, query, and fragment.
- Protected creation, metadata, analytics, and soft-disable operations.
- Optional expiration, explicit inactive-link responses, and a clock suitable for deterministic tests.
- Atomic creation and idempotent retry handling, with bounded collision retries in fresh transactions.
- Atomic request-count updates and a best-effort failure boundary after successful resolution.
- File-backed local H2 mode and a PostgreSQL deployment/test option.
- A same-origin demonstration interface, API/schema definitions, executable checks, scenario evidence, and practical review documentation.

The root README is the runnable entry point. [Architecture](ARCHITECTURE.md) covers request/data flows; [requirements](REQUIREMENTS.md) identifies assumptions; [the plan](ENGINEERING_PLAN.md) gives dependency order; [scenarios](SCENARIOS.md) links the greenfield and enhancement work; and [the AI record](AI_WORKFLOW.md) explains how the artifacts were produced.

## Main trade-offs

The single-database design makes durability and race handling understandable, but the database remains an availability dependency. Atomic SQL prevents lost successful increments, but a hot code can still serialize writes on one metrics row. Tolerating analytics failure protects an already-resolved redirect while allowing undercount and some failure-detection latency.

A shared key and process-local creation limit suit a controlled demonstration, but do not provide tenant isolation or global quotas. H2 makes local evaluation straightforward, but does not replace real PostgreSQL verification. Aliases remain reserved after expiry or disabling to prevent old links changing ownership, at the cost of indefinite identity retention.

## Verification status

The clean verification completed on 2026-10-06 with 44 default tests and six real PostgreSQL 17.6 tests passing, with no failures, errors, or skips; Checkstyle reported zero violations. The recorded coverage is 93.6% of lines and 80.1% of branches.

The live API demo, OpenAPI schema validation, narrow security guardrails, and 12 UI simulation groups passed. Independent HTTP/concurrency checks, a process-restart check, and real storage-failure injection also passed after fixing trailing-JSON and extreme-expiry validation defects. A 200-request, 10-client local H2 diagnostic returned 200 redirects and 200 recorded increments; its p95 was 25.35 ms. This short shared-machine sample does not establish production capacity or an SLO.

The preserved pre-alias baseline had 28 passing tests. A deliberately failing alias run, a source archive, and the actual scoped source patches support the brownfield scenario.

[Validation](VALIDATION.md) links commands, reports, environment details, scope, and limitations. Docker/Compose, the Testcontainers route, real-browser visual/accessibility verification, remote CI, comprehensive dependency-vulnerability scanning, and production deployment were not executed. No unexecuted gate is treated as passed.

## Risks and known limits

1. Counts represent recorded accepted GETs, not unique visitors or confirmed destination loads. They are unsuitable for billing or audit-grade analytics without redesign.
2. No public-abuse detection, domain reputation checking, takedown workflow, account model, or tenant authorization is implemented.
3. There is no distributed rate limiter, cache, durable analytics queue, automatic data retention, or production high-availability topology.
4. Local performance results apply only to their recorded environment and workload. Production latency, capacity, durability, and recovery objectives are not established.
5. Expiry or disable does not recall a response that already passed resolution.
6. A real release requires TLS, managed secrets, dependency/security review, monitoring, backup/restore practice, migration checks, and operational ownership.

## Human review required

Before adopting or presenting the project as personally owned work, inspect the source and actual diff, reproduce the important checks, and explain the invariants independently. Confirm the product assumptions, destination policy, privacy stance, retention model, and production requirements. Record real approvals only after they occur.

The project is complete as an engineering prototype when its runnable path and evidence are reproducible. That milestone is separate from approval to operate a public production service.

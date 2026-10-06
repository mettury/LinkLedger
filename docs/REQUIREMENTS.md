# Requirements and implementation assumptions

LinkLedger is a backend URL shortener prototype built to make the engineering decisions, code, validation, and use of AI reviewable. The service must create persistent short links, redirect visitors, expose basic analytics, and have explicit behavior when inputs or dependencies fail.

The requirements below are a sanitized interpretation of the project brief. Technology choices and detailed API semantics are project decisions. They have not been represented as answers from an interviewer or as human-approved production requirements.

## Required outcome

1. Deliver a runnable service with API and schema definitions, unit and integration tests, and setup instructions.
2. Explain the architecture, reliability boundaries, security risks, trade-offs, and operational limitations.
3. Break the work into dependency-ordered tasks with measurable acceptance criteria.
4. Demonstrate a greenfield implementation, a bounded change against a real earlier baseline, and a decision made under ambiguous requirements.
5. Record where AI contributed, what was changed or rejected, and which checks were actually executed.
6. Leave ownership and approval with the engineer. Generated code and documentation are review inputs; human engineering and release approval remain pending.

## Product scope

The prototype supports creation, resolution, and inspection of short links. Its intended deployment is a developer machine or controlled evaluation environment. Java 21 and Spring Boot implement a single application; JDBC and Flyway keep the persistence and schema lifecycle explicit. File-backed H2 offers a low-friction local mode, while PostgreSQL is the deployment-oriented database option.

A bundled browser demonstration client exercises the same API and is included for reviewer convenience. Public multi-tenant accounts, custom domains, campaign attribution, destination previews, billing, destination editing, physical deletion, and runtime AI are outside the implemented scope. A protected soft-disable operation preserves historical link identities. Redis and Kafka are potential later architectural choices, not runtime dependencies.

## Decisions made to resolve ambiguity

| Topic | Chosen behavior | Reason and consequence |
| --- | --- | --- |
| Link identity | Ordinary repeated creation requests create independent links. An explicit idempotency key represents a retry of the same operation. | Separate links preserve independent ownership and counting. Deduplication is not inferred from destination similarity. |
| Destination preservation | Preserve valid destination path, query string, and fragment. | Query values can identify a resource, and fragments can drive client-side routing. Removing them can change the user's destination. |
| URL safety | Accept structurally valid absolute HTTP or HTTPS destinations; reject credentials and unsafe input. Never fetch the destination. | Avoids a server-side fetch path and unsafe redirect-header values. This does not certify a destination as trustworthy. |
| Access model | Management operations require the configured API key; redirects are public. | A short link must work for its audience without exposing unauthenticated management. A shared key is appropriate only for a bounded prototype. |
| Analytics | Count accepted GET requests for active links, including repeats and bots. HEAD, missing links, and expired links do not count. | The service observes redirect requests, not successful page loads or unique human visitors. |
| Analytics failure | A counter-write failure must not prevent a redirect whose destination was already resolved. | Redirect availability takes priority; undercount is possible and must be observable. |
| Expiry | Use an injected UTC clock; a link is expired at or after its expiry instant. New expiry values must be future instants no later than the end of UTC year 9999. | One explicit boundary avoids clock-dependent tests and inconsistent interpretations of “expires at.” |
| Alias namespace | Custom aliases share the generated-code namespace; they cannot be reassigned just because a link expires. | Old bookmarks must never silently point to a different destination. Database uniqueness arbitrates concurrent claims. |
| Redirect status | Return a temporary redirect with caching disabled. | Avoids clients retaining a permanent mapping that bypasses expiry checks and request accounting. |
| Scale | Start with one application and one database; measure before introducing extra infrastructure. | A smaller failure surface can be validated within the prototype's scope. |

Exact syntax, limits, status codes, and representations belong to the checked-in API contract and implementation. The [architecture](ARCHITECTURE.md) explains the mechanisms behind these choices.

## Questions for an actual product owner

The following choices would materially change a deployed service. They remain open rather than silently becoming requirements.

- Who can create links, and is per-user or per-team ownership required?
- Is this a trusted internal service or an internet-facing shortener? Who owns abuse reports and link takedowns?
- What are the redirect volume, latency objective, availability target, and expected hot-link distribution?
- Must analytics be billable or exact, and how long may they lag? Is privacy-sensitive visitor information permitted at all?
- What retention, deletion, privacy, jurisdiction, and recovery requirements apply?
- Is a separate public short domain required? Which destinations, if any, must be disallowed?
- What maximum lifetime and alias reservation rules should the business enforce?
- What evidence would the organization require before production approval?

The present implementation is reversible where practical, but changing URL identity, alias reuse, or analytics semantics after adoption requires a versioned product decision.

## Acceptance trace

| Obligation | Repository evidence | What the reviewer should establish |
| --- | --- | --- |
| Runnable end-to-end service | Root README, application source, smoke script | A clean startup can create a link, redirect it, and inspect its statistics. |
| API and data correctness | OpenAPI contract, Flyway migrations, service and repository tests | Request validation and database constraints agree; query and fragment values survive resolution. |
| Requirements analysis | This document and [scenarios](SCENARIOS.md) | Assumptions are distinguished from prescribed outcomes; ambiguity has measurable semantics. |
| Dependency-ordered execution | [Engineering plan](ENGINEERING_PLAN.md) | Each step has inputs, constraints, acceptance criteria, and a validation gate. |
| Greenfield and brownfield work | [Scenarios](SCENARIOS.md) and baseline evidence | The brownfield example is a real bounded change against a preserved working baseline. |
| AI traceability | [AI workflow](AI_WORKFLOW.md) | Generated, revised, and rejected output is identified without inventing human approvals. |
| Reliability and safety | [Architecture](ARCHITECTURE.md), failure tests | Collisions, expiry, concurrent updates, and analytics failure have explicit behavior. |
| Repeatable quality gates | Build configuration, scripts, [validation record](VALIDATION.md) | Passed, failed, skipped, and unexecuted checks are reported separately. |
| Defensible handover | [Final summary](FINAL_SUMMARY.md), [interview walkthrough](INTERVIEW_WALKTHROUGH.md) | The engineer can explain both implemented guarantees and important limits. |

## Definition of completion

Completion means the deliverable is reproducible and its verification evidence is accurate. It does not mean every production capability has been implemented. A human engineer must inspect the code, reproduce the important checks, approve the chosen assumptions, and assess deployment-specific security and operational requirements before claiming ownership or readiness for a real release.

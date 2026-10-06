# Engineering plan

The plan sequences work so that each new behavior rests on a testable foundation. It is a breakdown of the delivered engineering approach, not a claim that a human spent a particular number of days on each step. The project brief's short implementation window favors one application, explicit SQL, and a narrow set of demonstrable guarantees.

## Dependency sequence

| Step | Depends on | Work and constraints | Acceptance and validation |
| --- | --- | --- | --- |
| 1. Define behavior | None | Separate required outcomes from assumptions. Specify URL identity, redirects, expiry, analytics, authentication, and deferred features. | Requirements and API semantics agree; no unstated exactness or production claims. |
| 2. Establish the build | 1 | Pin Java and framework dependencies. Add the test framework, static checks, coverage reporting, local configuration, and secret exclusions. | A clean compiler/test invocation works; missing required configuration fails clearly. |
| 3. Define persistence | 1–2 | Create a Flyway migration with primary/unique keys, destination, timestamps, expiry, and count fields. Support local and PostgreSQL modes. | Fresh schema creation succeeds; duplicate identities cannot persist; timestamp mapping is tested. |
| 4. Implement the greenfield path | 3 | Create random links, resolve active links, reject unsafe destinations, and increment counters atomically. Keep HTTP, policy, and SQL responsibilities separate. | Create, redirect, inspect, missing, expiry, unsafe-input, and HEAD cases pass. |
| 5. Verify failure behavior | 4 | Bound collision retries. Make counter writes fail-soft after resolution. Inject time. Check concurrent increments and protect management routes. | Tests demonstrate each boundary and each intentional error response. |
| 6. Preserve the baseline | 4–5 | Capture the working pre-alias implementation and its actual validation result before enhancement. | A reviewer can reconstruct the baseline and compare the actual changes. No reconstructed history is presented as an earlier commit. |
| 7. Add a bounded enhancement | 6 | Add custom aliases while preserving existing clients, redirects, and the baseline idempotency behavior. Let database constraints resolve races. | Old create requests still work. Alias validation, conflicts, idempotent replay, mismatched replay, and concurrency are covered. |
| 8. Reconcile the contract | 7 | Update API definition, examples, migrations, and docs. Inspect the source rather than repeating initial plans. | Fields, constraints, statuses, and examples reflect the final code. |
| 9. Run the quality gates | 7–8 | Run build/tests/static checks, smoke and performance checks, and PostgreSQL tests where the environment supports them. Capture failures and skipped stages. | The validation record gives commands, environment, outcomes, and limits for the final state. |
| 10. Prepare review | 9 | Write the scenario narratives, AI decision record, risks, and interview walkthrough. | Every claim has a corresponding artifact or is explicitly an assumption, proposal, or unverified limitation. Human approval remains pending. |

## Task contracts used for AI assistance

Effective tasks include an outcome, relevant source context, boundaries, and a way to detect failure. The following are concise descriptions of the task structure used for this project; they are not verbatim prompts or fabricated transcript records.

### Implementation task

- Intent: deliver a runnable shortener with reliable core behavior.
- Context: the sanitized requirement analysis, selected Java/Spring/JDBC stack, and the currently checked-in code.
- Constraints: preserve meaningful URL data; use database-enforced uniqueness; keep management protected; do not add Redis, Kafka, or a runtime LLM; never claim unexecuted tests passed.
- Acceptance: implement and test the documented API, preserve a real baseline before the enhancement, provide reproducible startup and checks, and report limits.

### Review task

- Intent: find behavior that contradicts the API contract or weakens reliability and safety.
- Context: final source, schema, tests, and run commands.
- Constraints: distinguish observed defects from speculation; do not publish or change unrelated artifacts; inspect edge cases as well as happy paths.
- Acceptance: return concrete affected paths, failure mechanisms, suggested correction, and a verification route.

### Documentation task

- Intent: give a reviewer enough evidence to understand and challenge the design.
- Context: complete requirement analysis, the implementation, baseline evidence, and actual execution results.
- Constraints: sanitize the source material; do not invent authorship, approval, commit history, prompts, or outcomes; align every detail with source.
- Acceptance: coherent architecture, requirements, scenarios, plan, AI trace, final summary, and practical interview preparation.

## Review gates and stop conditions

A failing test or static check blocks a clean verification claim. A skipped integration test is an unverified stage, even if the rest of the build succeeds. A benchmark requires a recorded workload and environment, and cannot establish a production SLO by itself.

A missing product answer does not block reversible prototype choices when the assumption is documented. A decision involving production access, privacy, destructive migration, public exposure, or a release requires actual authorization and human review. Passing local tests does not remove that requirement.

## Change safety

The brownfield change should be small enough to trace from request to persistence:

1. Identify the old behavior and retain a runnable baseline.
2. List impacted request DTOs, validation, service logic, SQL/schema, API contract, and tests.
3. Write compatibility and new-behavior cases before claiming success.
4. Implement the narrow change without rewriting unrelated modules.
5. Run the old regression suite and the new cases.
6. Review the diff and document operational consequences, including persistent alias reservations and idempotency-key retention.

The [scenario report](SCENARIOS.md) records how this sequence is evidenced in the actual repository.

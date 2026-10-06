# AI assisted engineering record

This project was generated and revised with AI assistance. The repository is intended for a human engineer to inspect, run, challenge, and adopt only after review. No human code review, interviewer approval, production deployment, or human-authored commit is claimed by these documents.

This is a concise, sanitized record of actual work and design decisions. It excludes confidential assignment text and raw conversation transcripts. Summarized task descriptions below are not offered as verbatim prompt evidence.

## Contributions and control points

AI assistance supported requirement interpretation, source changes, command execution, testing, documentation, and review. Repository source, stable API contracts, and actual run reports were reconciled before documenting specific guarantees.

The AI generated the initial application structure, SQL migration, tests, build/run support, and documentation. Review and execution results determine whether a generated proposal is retained, revised, or rejected. “Retained” in this record means included in this draft deliverable; it does not mean human-approved.

| Activity | Intent and guardrails | Evidence to inspect |
| --- | --- | --- |
| Requirement analysis | Identify prescribed outcomes separately from implementation assumptions; preserve privacy. | `REQUIREMENTS.md`, open product questions, and acceptance trace. |
| Architecture and decomposition | Keep the runnable scope small; specify dependency order, transaction boundaries, and failure semantics. | `ARCHITECTURE.md`, `ENGINEERING_PLAN.md`, package boundaries, and migrations. |
| Implementation | Build core APIs with persistent data and narrow responsibilities; do not add unvalidated distributed systems. | `src/main`, `pom.xml`, configuration, and API definition. |
| Test generation and execution | Exercise happy paths, boundaries, conflicts, concurrency, and injected failures; report skipped checks separately. | `src/test`, build reports, scripts, and `VALIDATION.md`. |
| Brownfield enhancement | Capture a working baseline and make a bounded alias change; preserve compatibility. | Baseline/diff evidence and the alias regression tests. |
| Documentation and review preparation | Match source and measured behavior; explain limitations and ownership candidly. | This document, scenarios, final summary, and interview walkthrough. |

## Decision trace

| Decision | Disposition in the deliverable | Reason and trace |
| --- | --- | --- |
| One application and a relational database | Retained | Provides database uniqueness and clear transactions with a small operational footprint; see repository/service boundaries. |
| Redis and Kafka as initial dependencies | Not adopted | No measured requirement justified their failure modes and deployment cost in this prototype. Scaling alternatives remain design notes. |
| Remove query strings and fragments during normalization | Rejected | These values can select a resource or client-side route. `UrlPolicy` preserves them and has regression tests. |
| Deduplicate every creation by normalized URL | Not adopted | Independent links have clear counting semantics. Retry deduplication is opt-in through an idempotency key. |
| Read a counter and write back count plus one | Rejected as an implementation approach | Concurrent requests can overwrite one another. The repository performs the increment atomically in SQL. |
| Block every redirect when analytics fails | Not adopted | The defined policy prioritizes an already-resolved redirect; the separate counter boundary records a failure signal. |
| Retry a failed insert inside the same transaction | Avoided | PostgreSQL can leave the transaction aborted. Each attempt gets its own transaction and rollback boundary. |
| Reuse expired or disabled aliases | Not adopted | Old links must not silently acquire a new owner or destination. Persistence preserves the reservation. |
| Claim “Base58” or “Base62” for the generator by convention | Corrected during documentation review | The actual source uses its own ambiguity-reduced alphabet. Documentation describes its observed length and properties rather than assuming a standard alphabet. |
| Describe idempotency as the brownfield change | Corrected during documentation review | Source inspection showed it already exists in the baseline. Custom-alias enablement is the bounded enhancement. |
| Treat H2 success as proof of PostgreSQL correctness | Rejected | A separate real PostgreSQL gate is needed for transaction, SQL, and concurrency semantics. |
| Describe the submission as production-ready or human-approved | Rejected | Local validation has a limited scope and human ownership review remains pending. |

The architectural alternatives above were assessed during this implementation and documentation work. They should not be mistaken for a transcript of generated code that was first committed and later reverted. Actual code changes and execution-driven corrections are recorded with their artifacts below.

## Actual iteration evidence

The following chronology is supported by the preserved repository artifacts and implementation report. Times are expressed in UTC; captured Maven logs use the environment's UTC−05:00 offset.

1. **Baseline execution, 2026-10-06 19:25:27:** the pre-alias test stage completed with 28 tests, no failures, no errors, and no skips. See [baseline output](evidence/baseline-test.log) and [per-class results](evidence/baseline-surefire/).
2. **Baseline capture, 19:26:11:** the build process created local commit `9d4b98308f3f78ca3143042f7f284d4369d8acc5` and a [preserved source snapshot](evidence/greenfield-baseline/). The recorded author is the build identity, not an attestation of human authorship.
3. **Red test run, completed 19:26:31:** `AliasTest` was added before alias enablement. Of 11 cases, one failed and two errored because the baseline returned the unsupported-alias response. The [red-stage output](evidence/brownfield-red-test.log) demonstrates that the positive/conflict tests were capable of detecting the missing feature. Eight input-rejection cases passed, so this run is not described as “all tests failed.”
4. **Enhancement:** an `AliasPolicy` and a bounded service change enable aliases in the existing code namespace. The [scoped alias patch](evidence/brownfield-alias.patch), [companion review patch](evidence/review-hardening.patch), and [final verification output](evidence/final-verify.log) preserve the affected source changes and post-change results. The alias patch also includes related hardening; these selected diffs are not portrayed as a complete repository-wide patch.

Execution and source review also led to concrete corrections:

- `ApiAccessFilter` now bounded-reads create bodies and rejects content over 8192 bytes even with chunked transfer. Real HTTP tests cover known-length and streamed requests.
- The actuator protection prefix was narrowed to the actual `/actuator` route tree so a valid public alias such as `actuatorPromo` does not accidentally require management authentication.
- Generated candidates also skip reserved application route names, avoiding a rare random allocation that would not behave like a public short link.
- Test configuration selects Mockito's subclass mock maker instead of depending on JVM self-attachment in this environment. This is test infrastructure, not a runtime security relaxation.
- Analytics response wording was changed from “successful redirects” to recorded GET resolutions so the API does not imply observed client delivery.
- Independent black-box HTTP review found that a valid JSON object followed by garbage could be accepted. Strict trailing-token rejection and a regression case were added.
- The same review found that an extreme parseable expiry could become a server error during database conversion. The service now rejects values after the end of UTC year 9999 before writing, with a regression case.
- Real PostgreSQL verification was extended to use native test binaries when Docker was unavailable; the final test contract also covers concurrent alias ownership and expiry/disable reservation. This verifies the database engine without claiming the Compose topology was executed.

The final clean verification completed at 19:33:41 UTC with 44 default-suite cases and six real PostgreSQL cases passing, with no failures, errors, or skips, and zero Checkstyle violations. The [validation record](VALIDATION.md) also records passing live-script checks, independently executed HTTP/restart/storage-failure checks, and 12 UI simulation groups. Docker topology, real-browser visuals, remote CI, comprehensive vulnerability scanning, and human approval remain unverified or pending. These corrections were made by AI-assisted implementation/review, and do not assert a human approval.

## Secure use of AI

- Provide only task-relevant, sanitized context. Internal assignment wording and unrelated private material are excluded from the public project.
- Use synthetic domains and test keys in examples. Keep real credentials out of code, prompts, logs, shell history, screenshots, and committed configuration.
- Treat suggested commands and dependencies as proposals requiring source inspection and appropriate verification.
- Do not grant AI-generated tests the status of an independent oracle. Inspect whether they actually exercise the intended path and whether mocks hide the important behavior.
- Keep publication, external communication, production access, destructive data changes, privacy decisions, and release approval under explicit human control.
- Record an unavailable or skipped quality gate as a limit. Do not weaken the definition of “passed” to make the summary look complete.

## Human ownership checklist

Before presenting this as personally owned engineering work, the engineer should:

1. Read the code and explain the invariants without relying on this narrative.
2. Run the documented clean setup, tests, and demonstration, including the PostgreSQL gate where available.
3. Review the actual baseline and enhancement diff.
4. Verify the API, privacy, abuse, and operational assumptions against the intended use.
5. Inspect dependencies, licenses, security results, and generated code quality under the organization's policy.
6. Make and record the real decision about suitability, residual risk, and any release.

The [interview walkthrough](INTERVIEW_WALKTHROUGH.md) is a study aid for this review. It is not permission to claim work or approvals that have not happened.

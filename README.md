# LinkLedger

A compact, runnable URL shortener built for an engineer-led, AI-assisted software engineering assignment. Java 21, Spring Boot, JDBC, Flyway and a deliberately small deployment surface.

The project includes a local dashboard, API, durable storage, analytics, idempotent creation, custom aliases, expiration, access control, tests, an OpenAPI contract and a documented engineering review trail. It is a prototype with explicit production gaps, not a claim of production certification.

## Start in two minutes

This repository contains the application source, tests, documentation and recorded verification evidence. Build the executable JAR locally; generated binaries are not committed.

Build and start it with a Java 21 JDK:

```bash
./mvnw clean verify
java -jar target/linkledger-1.0.0.jar
```

Then open **http://localhost:8080**. The wrapper downloads Maven and dependencies on the first build. On Windows, use `.\mvnw.cmd clean verify`.

### Build details

Requires Java 21. Building from source requires a **JDK**, not a JRE, plus internet access for the first dependency download. Maven is bootstrapped by the included wrapper.

### macOS / Linux

```bash
./mvnw clean verify
java -jar target/linkledger-1.0.0.jar
```

### Windows PowerShell

```powershell
.\mvnw.cmd clean verify
java -jar target\linkledger-1.0.0.jar
```

Open **http://localhost:8080**. The local-only demo key is **local-demo-key**. The server binds to loopback by default. Use the API-key field in the dashboard if you configure a different key. The UI does not persist keys to browser storage.

The build creates `target/linkledger-1.0.0.jar`. No separately installed Maven, database server, Docker, AWS account or AI API key is needed for the local demo; the included wrapper bootstraps Maven.

Local storage is a durable H2 file under `./data/`, relative to the directory where the application starts. Restart from the same directory to retain data. Only one process may open this file at a time. Stop with Ctrl+C. Do not delete `data/` unless you intend to discard your local demo records.

## Run the complete API demo

With the app running and Python 3 available:

```bash
python3 scripts/demo.py
python3 scripts/load_smoke.py
```

Use `python` instead of `python3` on Windows if needed. The scripts never follow external destinations. The demo checks creation, idempotent replay, HEAD exclusion, redirect target, analytics, authentication, disable and alias non-reuse. The load script is a 200-request local diagnostic, not a production benchmark.

### Create a link

```bash
curl -i http://localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -H 'X-API-Key: local-demo-key' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"url":"https://example.com/product?id=42#details","customAlias":"product-demo"}'
```

A first creation returns 201. Repeat the exact request and key for 200 with the existing link. Reuse that key with different request data for 409. Without an idempotency key, each request creates an independent link. Change the example alias/key between unrelated demos.

```bash
curl -i http://localhost:8080/product-demo
curl -I http://localhost:8080/product-demo
curl http://localhost:8080/api/v1/urls/product-demo/analytics -H 'X-API-Key: local-demo-key'
curl -i -X DELETE http://localhost:8080/api/v1/urls/product-demo -H 'X-API-Key: local-demo-key'
```

GET redirects count; HEAD does not. Expired or disabled links return 410. An unknown link returns 404. Query parameters, fragment, escaping and path case are preserved. No destination is fetched on the server.

The complete API schema is [openapi.yaml](src/main/resources/static/openapi.yaml), also served at `/openapi.yaml` while running.

## PostgreSQL with Docker Compose

Requires Docker with Compose. This is an optional local topology; it does not deploy to a cloud account.

```bash
cp .env.example .env
# Edit .env and replace both example values with your own local credentials.
docker compose up --build
```

On Windows use `Copy-Item .env.example .env`. The database is private to the Compose network. The app is published only on `127.0.0.1:8080`. PostgreSQL uses a named volume. `docker compose down` preserves that volume; adding `-v` deletes it, so do not use `-v` casually.

The Docker build runs the normal test and style gates. The `postgres` application profile requires a configured API key and database password. Enter your configured key in the dashboard. PostgreSQL container execution and embedded PostgreSQL testing are reported separately in [validation](docs/VALIDATION.md).

## Configuration

| Variable | Default / purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | Defaults to `local`; `postgres` uses an external database |
| `APP_API_KEY` | `local-demo-key` only in local profile; at least 12 characters |
| `APP_BASE_URL` | `http://localhost:8080`, fixed origin used to build short URLs |
| `PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1`; Compose explicitly uses `0.0.0.0` inside its private container |
| `APP_CREATE_LIMIT` | 60 authenticated creation attempts per minute, shared per instance |
| `DATABASE_URL` | JDBC PostgreSQL URL in `postgres` profile |
| `DATABASE_USERNAME` | `linkledger` in `postgres` profile |
| `DATABASE_PASSWORD` | Required in `postgres` profile |

When changing `PORT`, also change `APP_BASE_URL`. The app does not trust forwarded headers. Never expose the known local key on a public interface. A public deployment requires TLS, proper identity/authorization, abuse controls, secret handling, dependency review and operational ownership.

## Verification commands

```bash
./mvnw clean verify                     # Unit, H2 and real-HTTP tests; Checkstyle; coverage report
./mvnw -Ppostgres-tests verify          # Same suite + Testcontainers PostgreSQL 17.6 (Docker required)
./mvnw -Pembedded-postgres verify       # Same suite + native PostgreSQL 17.6 (Linux x86_64, non-root)
python3 scripts/security_check.py       # Narrow secret/execution-pattern checks, not a vulnerability scan
```

Use one PostgreSQL profile at a time. Native PostgreSQL binaries are a test-only Maven dependency, not bundled into the application. The embedded profile is a Linux x86_64 verification option; use the Docker profile on other platforms.

Reports: `target/surefire-reports/`, `target/failsafe-reports/`, and `target/site/jacoco/index.html`. CI is defined in `.github/workflows/verify.yml`; the workflow file is provided, but no remote CI run or repository publication is implied.

## Review path

1. [Requirements and assumptions](docs/REQUIREMENTS.md)
2. [Architecture and trade-offs](docs/ARCHITECTURE.md)
3. [Engineering plan](docs/ENGINEERING_PLAN.md)
4. [Greenfield, brownfield and ambiguous scenarios](docs/SCENARIOS.md)
5. [AI-assisted workflow and decisions](docs/AI_WORKFLOW.md)
6. [Validation and evidence](docs/VALIDATION.md)
7. [Interview walkthrough](docs/INTERVIEW_WALKTHROUGH.md)
8. [Final engineering summary](docs/FINAL_SUMMARY.md)
9. [Independent review and reproducible probes](docs/INDEPENDENT_REVIEW.md)
10. [Source provenance and repository import](docs/PROVENANCE.md)

Human review and sign-off remain explicit gates. The evidence describes actual work in this build session, not invented prior development history.

## Scope and attribution

The user-provided public [reference repository](https://github.com/laharichoudary5lp/assignment), observed at commit `7debb0d1f369029c513ba32d77ba97684d696717`, informed comparison of design choices. This implementation and its documentation were written independently; no reference Java source was copied. The supplied internal assignment text is not reproduced.

Maven Wrapper files retain the Apache project’s included notices. Frameworks and dependencies retain their own licenses. No public publication, assignment submission or cloud deployment has been performed.

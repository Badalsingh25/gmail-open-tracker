# Verification report (v0.5.0)

Environment: a cloud sandbox with Java 21, Node 22, Docker CLI (no daemon). **Maven Central was blocked (HTTP 403)**, so Spring
Boot dependencies could not be downloaded. npm worked.

## Executed and passing
| Check | Result |
|---|---|
| Pure backend logic (`core/`): token signer, IP ranges + list parsers, signal extractor, scorer, rate limiter, config checks | **38 tests pass**. Run by compiling with `javac` against a minimal local stand-in for JUnit's `@Test` and the assertion methods used |
| Extension library (`lib.js`): link collection, tracking rewrite, per-recipient rendering, MIME building and header-injection resistance | 8 tests pass |
| Content script against a **simulated** compose window: Send click, Ctrl+Enter, double click, separate copies, shared fallback, reply/unconfigured fallbacks, registration failure and retry, links off | 11 tests pass |
| `npm run build` (Vite) | Succeeds; manifest v0.5.0, permissions `storage, notifications, alarms, identity` |
| `node --check` on every extension script | Pass |
| `docker compose config` on `deploy/docker-compose.prod.yml` | Valid; `ingest` gets `TRACKER_MODE=ingest, DDL_AUTO=none`, `api` gets `api, update` |
| `calibrate.mjs` on synthetic sample data | Runs and prints the expected report |
| Syntax-only `javac` over all backend and test sources; every unresolved symbol is a Spring/Jakarta/JPA type | No syntax errors, no unresolved references to the project's own types |

## Not executed
- **`mvn clean test`**: the Spring/JPA layer (entities, repositories, services, controllers, filter, scheduling, mode gating) and
  its tests (`TrackerFlowIntegrationTest`, 11 full-flow tests on H2) are written but **never compiled or run**. Expect to fix a
  few compile or query errors on first run. Start there.
- JPQL in `TrackedMessageRepository.search` and the `@Modifying` queries have not been run against H2 or MySQL.
- `docker build` / `docker compose up`: not run. The Dockerfile has not been built.
- The Apple/Google list download in `IpListHolder`: not run against the live URLs.
- Real Gmail: the extension has not been loaded into Chrome or used on a real compose window; Gmail API sending and Google sign-in not exercised.
- Real mail clients: no calibration data exists. No accuracy figure is claimed anywhere.
- The `identity`/`oauth2` manifest block was not loaded into Chrome.

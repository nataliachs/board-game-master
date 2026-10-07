# Development Status

## Current milestone
**Milestone 0 — Project Bootstrap (complete and validated)**

## Milestones
- [x] Milestone 0 — Project Bootstrap
- [ ] Milestone 1 — Game Domain
- [ ] Milestone 2 — Rule Documents
- [ ] Milestone 3 — Embeddings and Vector Search
- [ ] Milestone 4 — RAG Question Answering
- [ ] Milestone 5 — RAG Improvements
- [ ] Milestone 6 — Game Sessions
- [ ] Milestone 7 — Guided Setup
- [ ] Milestone 8 — Voice

## Current work
- Java 21 Spring Boot Gradle application and checksum-pinned Gradle wrapper.
- Feature package boundaries, local/test/production configuration, ECS JSON logging, and request IDs.
- Docker Compose PostgreSQL 17 with pgvector, persistent storage, localhost binding, and readiness check.
- Flyway V1 installs the vector extension; Hibernate validates schema and open-in-view is disabled.
- Actuator health, local Swagger/OpenAPI, shared API error DTO, MVC advice, and servlet-error fallback.
- Fast error-handling tests and Docker-required Testcontainers startup/HTTP/migration tests.
- README setup, environment, test, and manual demonstration instructions.

No Game/GameVersion entities, game tables, game endpoints, Catan data, RAG, sessions,
setup behavior, or AI/speech providers have been implemented. Milestone 1 remains unstarted.
No Git commit has been made; existing repository content and history were preserved.

## Decisions
- Modular monolith, organized by feature.
- Game identifies the logical board game; GameVersion identifies its edition/language/ruleset.
- Customers normally select Game only; the backend resolves a default GameVersion and pins it to the session.
- Catan is the first reference dataset; shared engines are game-independent.
- Implement only the requested milestone; stop before the next.
- Root package is `com.boardgamemaster`; feature subpackages contain only reserved documentation until their milestones.
- Spring Boot 3.5.16, Gradle 8.14.4, springdoc 2.8.17, Java 21 toolchain; JUnit 5 and Boot-managed Testcontainers.
- `test` runs without Docker; `integrationTest` requires it. `check` includes both and does not silently skip integration tests.
- Local profile imports `.env`; production requires external database credentials and disables API documentation by default.
- No application schema or vector index is needed in Milestone 0. Domain tables/indexes are deferred.

## Validation
Validation on 2026-10-07 using Temurin JDK 21.0.12.1 and the Gradle 8.14.4 wrapper:

- `gradlew.bat test bootJar` — PASS. Six unit tests passed (four MVC/validation/error/request-ID tests and two servlet-error fallback tests); executable JAR built successfully.
- `docker compose config --quiet` — PASS with a temporary process-only password. No `.env` file or credentials were written to the repository.
- `docker manifest inspect pgvector/pgvector:0.8.2-pg17` — PASS; configured image tag exists.
- Initial database and full-check attempts failed because Docker Desktop could not start. Retried on 2026-10-07 after Docker/WSL became available; results below supersede that environment limitation.
- `gradlew.bat check bootJar` retry — PASS. All three PostgreSQL/pgvector integration tests passed with zero skips or failures; six unit tests remained passed/up to date and the executable JAR remained successfully built.
- Integration tests verified Flyway V1, usable vectors, idempotent migration rerun, Spring Boot startup with Hibernate validation, HTTP health, OpenAPI, Swagger, and consistent missing-resource errors. No paid provider calls were made.
- `docker compose -p <unique-validation-project> up -d --wait` — PASS using an isolated database on localhost port 55432 and a random process-only password. The packaged JAR started with the default local profile on port 18080.
- Manual packaged-app HTTP checks — PASS: `/actuator/health` returned 200 and `UP`; `/v3/api-docs` and `/swagger-ui/index.html` returned 200; `/api/v1/games` returned 404 with `NOT_FOUND`.
- Direct Compose database checks — PASS: Flyway migration version 1 was successful and pgvector extension version 0.8.2 was installed.
- Temporary application process, Compose container, network, and validation-only volume were removed after checks. No existing project volumes or services were altered, and no `.env` or persistent credentials were created.
- `AGENTS.md` and `PROJECT_SPEC.md` remain byte-for-byte identical to the prepared repository. No commit, reset, or staging operation was performed.
- Installed-JDK verification on 2026-10-07 — PASS: `gradlew.bat check bootJar --rerun-tasks --no-daemon` using `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (Temurin 21.0.12.1). All six unit tests and three integration tests reran successfully, with no failures or skips; executable JAR rebuilt. The Gradle daemon log confirms the installed JDK was used.

## Known issues
- The previous Docker/WSL blocker is resolved. No remaining Milestone 0 acceptance blockers are known.
- Docker access and localhost checks required execution outside the sandbox. Future integration checks need access to the running Docker Linux engine.
- Validation services are stopped; follow the README setup instructions to start a local instance. Milestone 1 remains unstarted.
- JDK 21 is now installed and machine-level `JAVA_HOME` and PATH are configured correctly. This existing Codex session retained the old environment; validation loaded the installed `JAVA_HOME` into the process explicitly. Open a fresh PowerShell window or restart Codex to inherit the updated environment. The earlier portable tooling/cache remains outside the repository at `D:\Projects\.bootstrap-tools`.

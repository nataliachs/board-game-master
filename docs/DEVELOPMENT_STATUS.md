# Development Status

## Current milestone
**Milestone 1 — Game Domain (complete and validated)**

## Milestones
- [x] Milestone 0 — Project Bootstrap
- [x] Milestone 1 — Game Domain
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

- Generic Game and GameVersion JPA entities, repositories, transactional service, and DTO-based REST APIs.
- Flyway V2 catalog tables, foreign key, player/metadata checks, unique slugs/version identities, and an active single-default constraint.
- Flyway V3 Catan reference metadata with one active 5th Edition / English default; no rule content.
- Create/read games and versions, bounded game pagination, ownership validation, default resolution with optional player-count validation, and atomic/idempotent default selection.
- Domain validation and HTTP/database integration tests, including concurrent default selection and V1-to-V3 upgrade preservation.

Milestone 2 remains unstarted. No rules ingestion, embeddings, RAG, sessions, setup behavior,
or AI/speech integrations were implemented. Work remains on `milestone-1-game-domain`.
No commit, push, staging operation, or branch change was performed during Milestone 1 work.

## Decisions
- Modular monolith, organized by feature.
- Game identifies the logical board game; GameVersion identifies its edition/language/ruleset.
- Customers normally select Game only; the backend resolves a default GameVersion and pins it to the session.
- Catan is the first reference dataset; shared engines are game-independent.
- Implement only the requested milestone; stop before the next.
- Root package is `com.boardgamemaster`; the game feature is implemented, while later feature packages remain reserved.
- Spring Boot 3.5.16, Gradle 8.14.4, springdoc 2.8.17, Java 21 toolchain; JUnit 5 and Boot-managed Testcontainers.
- `test` runs without Docker; `integrationTest` requires it. `check` includes both and does not silently skip integration tests.
- Local profile imports `.env`; production requires external database credentials and disables API documentation by default.
- Versions store an owning game UUID; the FK and scoped reads prevent cross-game assignment. Default writes lock the parent game and use a partial unique index as a database invariant.
- Games may be registered without a default. Resolution requires active game/default metadata and returns a meaningful conflict when unconfigured. Creating a default version replaces the previous one atomically.
- Catalog reads expose inactive metadata for configuration; there are no general update/delete/activation endpoints yet.
- Catan's seeded 5th Edition / English version is the selected reference configuration; release year is intentionally unspecified and duration is a demo estimate. No Catan-specific Java logic exists.

## Validation

### Milestone 1

Validation on 2026-10-07 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- `gradlew.bat check bootJar --no-daemon` — PASS. All 40 tests passed: 27 unit tests and 13 PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully.
- Domain coverage: metadata, player boundaries, language normalization, required ownership, release year, and inactive-default rejection.
- Real HTTP coverage: seed data, OpenAPI paths, game/version registration and reads, Location headers, pagination, DTO validation, malformed UUIDs, duplicates, foreign ownership, inactive/missing defaults, and player count rejection.
- Transaction/database coverage: default switching, idempotent selection, creation replacing the default, concurrent selections retaining exactly one default, FK/uniqueness/check constraints, and failed assignment preserving existing defaults.
- Migration coverage: fresh startup with Hibernate validation; V1-to-V3 upgrade preserving an existing marker table/data; migration validation and repeat runs without duplicate seed data.
- Existing bootstrap health/Swagger/vector/error tests also passed. No paid AI calls were made, and isolated Testcontainers databases were used rather than the user's local Compose database.
- Manual demonstration commands are in README. An already-running local application must be restarted to load the new code and apply V2/V3 to its retained database volume; that user database was not changed by these checks.

### Milestone 0 history

Earlier bootstrap validation on 2026-10-07 using Temurin JDK 21.0.12.1 and Gradle 8.14.4 (nine tests before catalog implementation):

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
- No remaining Milestone 1 acceptance blockers are known. Docker/WSL is working.
- Docker access and localhost checks required execution outside the sandbox. Future integration checks need access to the running Docker Linux engine.
- The catalog is local-development functionality without authentication, matching the existing bootstrap. General edit/delete/activation operations, full BCP 47 language registry validation, and later-milestone functionality remain outside this implementation.
- JDK 21 is now installed and machine-level `JAVA_HOME` and PATH are configured correctly. This existing Codex session retained the old environment; validation loaded the installed `JAVA_HOME` into the process explicitly. Open a fresh PowerShell window or restart Codex to inherit the updated environment. The earlier portable tooling/cache remains outside the repository at `D:\Projects\.bootstrap-tools`.

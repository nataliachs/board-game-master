# Board Game Master — Development Status

## Product Direction

Board Game Master uses a teaching-first customer experience.

Default flow:

Select Game

→

Teaching

→

Guided Setup

→

Continue Teaching

→

Ready to Play

→

In-Game Rule Q&A

Customers may ask clarification questions while learning.

Questions must not reset TeachingFlow or SetupFlow progress.

STANDARD teaching depth is the default customer experience.

The product direction is planned behavior. Milestones 0–4 are complete and validated with
provider doubles. Teaching, sessions, setup, voice and browser UI are not implemented yet.

## Current Milestone

**Milestone 4 — RAG Question Answering (complete and validated with provider doubles)**

Milestone 5 remains unstarted. Live OpenAI testing for Milestone 3 is deferred.

## Milestones

- [x] Milestone 0 — Project Bootstrap
- [x] Milestone 1 — Game + GameVersion Domain
- [x] Milestone 2 — Rule Documents + Ingestion
- [x] Milestone 3 — Embeddings + Vector Search
- [x] Milestone 4 — RAG Question Answering
- [ ] Milestone 5 — Production-Quality RAG
- [ ] Milestone 6 — Game Sessions + Teaching Flow
- [ ] Milestone 7 — Guided Setup Integrated with Teaching
- [ ] Milestone 8 — Voice + Minimal Demo UI

## Milestone 0 — Project Bootstrap

Status: Complete and validated; implementation details and historical results are retained below.

Expected:

- Java 21
- Spring Boot
- Gradle
- PostgreSQL
- pgvector
- Docker Compose
- Flyway
- OpenAPI
- global API error foundations
- `.env.example`
- `.gitignore`
- README
- initial tests

---

## Milestone 1 — Game + GameVersion Domain

Status: Complete and validated; implementation details and historical results are retained below.

Expected:

- generic Game model
- generic GameVersion model
- repositories
- services
- DTOs
- validation
- REST APIs
- Flyway migrations
- tests
- Catan created as first reference Game
- one default Catan GameVersion

Catan must be application data, not shared infrastructure.

---

## Milestone 2 — Rule Documents + Ingestion

Status: Complete and validated; implementation details and historical results are retained below.

Expected:

- RuleDocument
- RuleChunk
- generic ingestion pipeline
- text cleanup
- chunking
- source metadata
- GameVersion association
- tests

---

## Milestone 3 — Embeddings + Vector Search

Status: Complete and validated; implementation details and results are retained below.

Expected:

- EmbeddingClient abstraction
- provider adapter
- pgvector storage
- semantic similarity queries
- GameVersion filtering
- integration tests
- prove retrieval works without an LLM

---

## Milestone 4 — RAG Question Answering

Status: Complete and validated with provider doubles. Live provider testing is deferred.

Expected:

- LlmClient abstraction
- prompt builder
- retrieved-context injection
- grounded answers
- citations
- confidence handling
- low-confidence fallback
- question API
- tests

---

## Milestone 5 — Production-Quality RAG

Status: Unstarted. Expected scope only; this documentation update does not implement it.

Expected:

- metadata filtering
- language support
- document-type filtering
- expansion-ready filtering
- configurable top-K
- similarity threshold
- duplicate suppression
- reranking abstraction
- retrieval evaluation dataset
- improved confidence logic

---

## Milestone 6 — Game Sessions + Teaching Flow

Status: Unstarted. Expected scope only; this documentation update does not implement it.

Expected:

- GameSession
- ConversationMessage
- SessionStage
- teachingDepth
- TeachingFlow
- TeachingSection
- teaching progress
- session context
- conversation context
- questions during teaching
- resume teaching after Q&A
- transition toward playing

Customer experience must be teaching-first.

---

## Milestone 7 — Guided Setup Integrated with Teaching

Status: Unstarted. Expected scope only; this documentation update does not implement it.

Expected:

- SetupFlow
- SetupStep
- setup state
- setup progression
- previous/next/repeat/restart
- TeachingFlow → SetupFlow integration
- SetupFlow → TeachingFlow resume behavior
- Catan setup flow as reference data
- tests

---

## Milestone 8 — Voice + Minimal Demo UI

Status: Unstarted. Expected scope only; this documentation update does not implement it.

Expected:

Voice:

- SpeechToTextClient
- TextToSpeechClient
- provider adapters
- push-to-talk interaction
- reuse existing Game Master pipeline
- error handling
- tests with mocks

Demo UI:

- browser-based interface
- Spring Boot + Thymeleaf
- game selection
- player count
- QUICK / STANDARD / DETAILED teaching depth
- STANDARD default
- teaching screen
- teaching progress
- question input during teaching
- guided setup screen
- ready-to-play transition
- gameplay Q&A
- citations
- conversation history
- voice interaction
- transcription display
- audio response playback
- review previously taught rules
- end session

The application should be demonstrable without Swagger or Postman.

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
- Domain validation and HTTP/database integration tests, including concurrent default selection and V1-to-current upgrade preservation.

- Version-scoped RuleDocument and RuleChunk JPA persistence and Flyway V4, including a composite document/version FK and unique ordered chunk indices.
- Reusable transactional ingestion service accepting source metadata, a filename and bytes, independent of HTTP. Imports store cleaned source and all chunks atomically.
- Strict UTF-8 .txt/.md import, BOM/line-ending/trailing-whitespace cleanup, structural section/paragraph chunking, sentence/word fallback, Unicode-safe long-token splitting, and bounded whole-part overlap.
- Source type/version/language/reference, sanitized basename, import timestamp, section/page metadata, and exact UTF-16 source offsets. Document language must match the selected version.
- Multipart import and scoped metadata/source/chunk reads, bounded pagination, OpenAPI documentation, safe errors, validated startup configuration, and byte/chunk limits.
- Original CC0 fictional Lantern Trail sample and local import instructions. No commercial rulebooks or source files are automatically imported.
- Unit and isolated PostgreSQL ingestion tests, including real HTTP multipart handling, malformed input, version isolation, database constraints, transactional rollback, and V3-to-V4 upgrade preservation.

- Provider-neutral EmbeddingClient, validated immutable normalized vectors and provider/model/dimension profiles.
- Opt-in OpenAI HTTP adapter with bounded input/timeouts, safe provider errors and disabled-by-default configuration.
- Flyway V5 stores per-chunk profile-specific vectors with version ownership and dimension/nonzero constraints.
- Explicit document indexing/status endpoints, reuse of existing embeddings and atomic batch persistence after provider calls.
- RuleRetrievalService performs version/profile-scoped exact cosine ranking with bounded top-K and traceable source metadata.
- Retrieval-only REST API, OpenAPI contracts, operational timing logs and deterministic isolated tests without paid calls.

- Provider-neutral LlmClient/request/response contracts and disabled-by-default OpenAI Responses adapter.
- Dedicated evidence prompt builder, bounded whole-chunk context, strict structured output and validated source citations/quotes.
- Game question endpoint resolves the active default version and returns an answer with evidence-based HIGH/MEDIUM confidence, or a fixed LOW insufficient-evidence fallback.
- Empty/weak evidence skips generation; refusal or invalid citations discards generated text. Provider outages retain safe meaningful errors.
- Unit provider/prompt/confidence tests and isolated real-HTTP/PostgreSQL question pipeline tests, including permitted original Catan input.

Milestone 5 remains unstarted. No reranking, production retrieval evaluation, metadata filters,
sessions, teaching, setup, voice or browser UI were implemented. Work remains on
`milestone-4-rag-question-answering`. No commit, push, staging operation or branch change was
performed during Milestone 4 work. Live OpenAI testing for Milestone 3 remains deferred.

## Major Architectural Decisions

Teaching, setup, voice and demo UI decisions below describe planned work in Milestones 6–8;
they do not change the implementation status of the completed catalog/ingestion features.
- Modular monolith, organized by feature.
- Game identifies the logical board game; GameVersion identifies its edition/language/ruleset.
- Customers normally select Game only; the backend resolves a default GameVersion and pins it to the session.
- Catan is the first reference dataset; shared engines are game-independent.
- Implement only the requested milestone; stop before the next.
- Root package is `com.boardgamemaster`; game, rules, embedding AI and retrieval features are implemented, while later feature packages remain reserved.
- Spring Boot 3.5.16, Gradle 8.14.4, springdoc 2.8.17, Java 21 toolchain; JUnit 5 and Boot-managed Testcontainers.
- `test` runs without Docker; `integrationTest` requires it. `check` includes both and does not silently skip integration tests.
- Local profile imports `.env`; production requires external database credentials and disables API documentation by default.
- Versions store an owning game UUID; the FK and scoped reads prevent cross-game assignment. Default writes lock the parent game and use a partial unique index as a database invariant.
- Games may be registered without a default. Resolution requires active game/default metadata and returns a meaningful conflict when unconfigured. Creating a default version replaces the previous one atomically.
- Catalog reads expose inactive metadata for configuration; there are no general update/delete/activation endpoints yet.
- Catan's seeded 5th Edition / English version is the selected reference configuration; release year is intentionally unspecified and duration is a demo estimate. No Catan-specific Java logic exists.
- Rule documents belong to a GameVersion; chunks reference both document and version through a composite FK. The owning game/edition resolves through GameVersion. No redundant game ID or embedding fields are introduced.
- Structural chunking uses ATX Markdown headings and paragraphs, with sentence/word fallback. It is deterministic and does not call AI. Fenced code remains text; this is not a full Markdown renderer. Overlap is a maximum budget and may be zero when no whole trailing part fits.
- Optional standalone `<!-- page: N -->` markers provide explicit page metadata; unknown pages remain null. Stored normalized text retains markers, while chunks exclude them. Source offsets are zero-based UTF-16 with exclusive ends, matching Java and JavaScript substring indexing.
- Ingestion uses validated `boardgame.rules.*` / `RULE_*` settings: 1200-character chunks, 200-character overlap budget, 1 MiB input limit and 10000 chunks by default. Servlet file/request limits remain 10 MiB. `RAG_TOP_K` is active for retrieval; other reserved RAG settings remain inactive.
- Metadata requires a source reference and document version; language must match the selected version. Inactive versions may be prepared. Duplicate imports create independent documents. URLs are metadata only; uploaded basenames are sanitized and no server filesystem paths are read.
- PDF extraction, document edit/delete, deduplication, background ingestion and full Markdown parsing are deferred. No copyrighted commercial rule source is bundled. Original demo text is CC0 and should be imported into a separate demo game/version.
- Embeddings use a separate vector table keyed by chunk/provider/model/dimensions; a composite FK preserves version ownership. Changing the active profile requires explicit reindexing and cannot mix incompatible vector spaces.
- Exact cosine retrieval is intentional at this milestone's scale. A B-tree index supports version/profile filtering; a materialized candidate query isolates dimensions before distance evaluation. No approximate vector index, threshold filtering or reranking is introduced.
- Provider calls happen outside database transactions. A short locked transaction persists the complete generated batch atomically and handles concurrent duplicate indexing idempotently. Concurrent callers can still incur duplicate provider costs before persistence.
- Indexing is explicit and synchronous, bounded by document chunk count and per-input characters. Existing imports are preserved and remain unindexed until requested. Ingestion does not call a provider.
- Embedding configuration defaults to disabled; blank legacy provider/model/dimensions values retain safe defaults. An enabled provider requires a key. OpenAI HTTP requests have a fixed endpoint, no redirect following and bounded timeouts; errors never include upstream bodies or credentials.
- The retrieval endpoint returns chunks, source metadata and cosine scores only. The separate question endpoint adds evidence-grounded generation and baseline confidence; similarity scores are not calibrated factual probabilities.
- Stateless questions resolve the game's active default once per request. All supplied evidence and citations belong to that version. Sessions/conversation history remain Milestone 6; no new migration or dependency is required for Milestone 4.
- The prompt builder keeps trusted instructions separate from JSON-encoded untrusted questions/documents. It requests evidence-only answers, exact supporting quotes and explicit insufficiency; bounded whole chunks preserve retrieval order.
- LLM-generated citation IDs and quotes are checked against the exact prompt evidence. Public source metadata always comes from storage. Unknown/excluded sources, invented quotes, duplicate citations, weak cited evidence or model refusal produce the fixed LOW fallback with no sources.
- `boardgame.answer.*` gates default to 0.5 minimum and 0.8 HIGH. Generation requires usable evidence; accepted confidence uses the weakest cited score plus model-reported sufficiency. These are baseline answer acceptance heuristics, not Milestone 5 retrieval filtering or validated probabilities.
- The opt-in LLM adapter uses Responses strict structured outputs, temperature 0, bounded tokens, `store=false`, fixed HTTPS, no tools/redirects/retries and safe errors. Both providers default to disabled. External calls run outside DB transactions; timing logs omit text/keys. Legacy `AI_*` and proposed retrieval threshold settings remain reserved.

### Multi-Game Architecture

The platform is designed for multiple games.

Catan is the first reference implementation only.

### Game vs GameVersion

Game represents the logical game.

Example:

Catan

GameVersion represents a specific ruleset.

Example:

Catan — 5th Edition — English

Customers normally select Game.

The backend resolves GameVersion.

### TeachingFlow vs RAG

TeachingFlow controls:

- what is being taught
- teaching order
- teaching progress

RAG controls:

- retrieval of verified rule knowledge

The two responsibilities must remain separate.

### SetupFlow

Setup progress is deterministic application state.

It must not depend on LLM memory.

### Voice

Voice is another interface to the existing Game Master pipeline.

It must not contain separate game logic.

### Demo UI

The Phase 5 UI is a lightweight demonstration/testing interface.

It must remain thin and reuse backend application logic.

## Validation

### Milestone 4

Validation on 2026-10-07 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- The first unit run exposed a fixture using an unsaved GameVersion without an ID; the fixture was corrected. A subsequent full run passed all 137 unit tests and 42/51 integration tests; nine new integration cases failed while replacing an existing Mockito answer with `when`, which invoked the prior answer with a null matcher argument. Provider doubles were corrected to use `doAnswer`/`doReturn`/`doThrow`. The final passing full run below supersedes these intermediate fixture failures.
- Final `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 188 tests passed: 137 unit and 51 PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully. Existing Milestone 0–3 migration, ingestion, catalog and retrieval tests continue to pass.
- Local HTTP provider-contract tests cover structured schema, fixed low randomness, nonstored requests, output message parsing, refusal, malformed/incomplete envelopes/contracts, safe HTTP/rate-limit/timeout failures, disabled/blank legacy configuration, startup bounds, immutable contracts and redacted diagnostics.
- Application unit tests cover confidence boundaries, weakest cited score, empty/weak evidence avoiding generation, refusal, invented/excluded IDs or quotes, duplicate citations, provider errors, question limits, JSON-escaped prompt-injection text, whole-chunk context budgets and cross-version rejection.
- Real HTTP/PostgreSQL tests cover active default resolution, traceable cited answers, another game/edition exclusion, default changes between stateless requests, empty indexes without provider calls, weak evidence, unsupported questions, injected documents with invented citations, embedding/LLM errors, missing/inactive/unconfigured games, validation, OpenAPI and the seeded Catan version with an original narrow test paraphrase rather than a copied commercial source.
- Tests use local provider HTTP fixtures and isolated Testcontainers databases. No paid calls or real API keys are required. The existing Compose database, `.env`, local services and user data are not changed.
- Live OpenAI testing for Milestone 3 remains deferred. Milestone 4 live interoperability and generated-answer quality are also deferred; automated tests establish contracts/orchestration rather than live-model semantic quality.
- `git diff --check` — PASS. AGENTS.md, PROJECT_SPEC.md, prior migrations and build dependencies are unchanged. Changes remain unstaged and uncommitted on `milestone-4-rag-question-answering`; no branch change or push occurred. Milestone 5 remains unstarted. README includes opt-in manual question testing and its costs/limitations.

### Milestone 3

Validation on 2026-10-07 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Final `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 106 tests passed: 76 unit tests and 30 PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully.
- Provider tests use a local HTTP fixture: request contract, success, invalid response/model/index/dimensions, nonfinite/zero vectors, HTTP failures, rate limiting, timeout, input limits, startup validation, blank legacy settings and credential redaction. Extreme finite vectors normalize without overflow/underflow.
- Retrieval tests prove known-vector cosine ordering, deterministic ties, source traceability, top-K bounds and exclusion across games, editions, providers, models and dimensions without an LLM. Empty indexes avoid query-provider calls.
- Indexing tests cover reuse/idempotency, whole-document input validation before provider calls, provider failure preserving prior data, atomic database rollback, concurrent duplicate indexing and ownership/vector constraints.
- Real HTTP tests exercise status, indexing, retrieval, validation, ownership errors, disabled provider, safe timeout responses and OpenAPI. Migration tests preserve existing documents/chunks on V4-to-V5 upgrade, rerun idempotently, and retain V1-to-current catalog upgrade coverage. Existing Milestone 0–2 checks continue to pass.
- Checks require local Gradle daemon/Docker access outside the sandbox and use isolated Testcontainers databases. No paid calls, real provider keys or live OpenAI service validation were used. The existing Compose database, `.env`, local services and persisted user data were not changed.
- `git diff --check` — PASS. AGENTS.md and PROJECT_SPEC.md remain unchanged. Changes remain unstaged and uncommitted on `milestone-3-embeddings`; no push or branch change was performed. Restart the local app to apply V5 before manual use; enable/configure embeddings explicitly for live indexing/search.

### Documentation revision — 2026-10-07

- Updated PROJECT_SPEC.md, AGENTS.md, this status file and README.md for the teaching-first journey.
- Replaced customer modes with QUICK / STANDARD / DETAILED teaching depth, STANDARD by default.
- Planned explicit application-owned SessionStage, version-bound TeachingFlow/TeachingSection, questions that preserve progress, setup interruption/resume, and the same-session ready-to-play transition.
- Milestone 6 now covers sessions and teaching, Milestone 7 integrates setup with teaching, and Milestone 8 covers voice plus a thin Spring Boot/Thymeleaf demo UI.
- Milestones 0–2 and their recorded validation remain complete. Milestone 3 remains unstarted. No source, tests, migrations, dependencies, configuration or other documentation files were changed in this revision.
- Documentation consistency/whitespace and file-preservation checks were performed. Application tests were not rerun for these documentation-only edits; the 64-test result below belongs to the earlier Milestone 2 implementation.
- No commit, push, staging or branch change was performed.

### Milestone 2

Validation on 2026-10-07 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Initial sandboxed Gradle attempt could not connect to its local daemon; rerun with local daemon/Docker access proceeded. This is an execution-environment limitation, not a project dependency issue.
- First full run passed unit tests and 18/19 integration tests. One new test fixture omitted the catalog's required duration; it was corrected. The subsequent full check passed, followed by a final full check after an indentation regression fix. Final results below supersede that intermediate failure.
- Final `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 64 tests passed: 44 unit tests and 20 PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully.
- Unit coverage includes BOM/CRLF/trailing-space cleanup, retained first-line indentation, heading/page boundaries, code fences, paragraph overlap with forward progress, sentence splitting, long Unicode tokens, exact offsets, malformed UTF-8/control characters, unsupported files, empty/marker-only content, input/chunk limits, configuration and metadata validation.
- Integration coverage includes multipart import/read through MVC and real HTTP, source metadata/offsets, chunk order/pagination, OpenAPI routes, language mismatch and safe errors, servlet/application upload limits, isolation across games and editions, database FK/order/content/offset constraints, rollback after a simulated chunk-write failure, fresh Hibernate schema validation, and V3-to-V4 upgrade preserving catalog data. Existing catalog/bootstrap tests continue to pass, including V1-to-current migration preservation.
- At Milestone 2 implementation completion, `git diff --check` — PASS and `AGENTS.md`/`PROJECT_SPEC.md` were unchanged. They were subsequently revised by the separate documentation-only update above. Branch remains `milestone-2-rule-documents`; changes are unstaged and uncommitted. No provider calls were made.
- Tests use isolated Testcontainers databases. The existing Compose database, `.env`, running local application, and persisted user data were not changed. Restart the local app to apply V4 before manual testing.

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
- At Milestone 0 validation, `AGENTS.md` and `PROJECT_SPEC.md` were byte-for-byte identical to the prepared repository. No commit, reset, or staging operation was performed during that implementation.
- Installed-JDK verification on 2026-10-07 — PASS: `gradlew.bat check bootJar --rerun-tasks --no-daemon` using `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` (Temurin 21.0.12.1). All six unit tests and three integration tests reran successfully, with no failures or skips; executable JAR rebuilt. The Gradle daemon log confirms the installed JDK was used.

## Known issues

- No remaining automated Milestone 4 acceptance blockers are known. Docker/WSL is working. Milestone 5 remains unstarted; live provider validation remains deferred.
- Docker access and localhost checks required execution outside the sandbox. Future integration checks need access to the running Docker Linux engine.
- Catalog, rule import, indexing, retrieval and question answering are local-development functionality without authentication. PDF extraction, edit/delete APIs, full BCP 47 registry validation and later-milestone functionality remain outside this implementation. Repeat imports create additional documents; there is no deduplication yet.
- Live provider interoperability and semantic quality were not tested with a real API key. Tests establish the HTTP contract and deterministic database retrieval; they do not measure real-model relevance. Provider token limits can still reject text because local bounds count characters, not tokens.
- Live OpenAI testing for Milestone 3 is explicitly deferred, as requested; Milestone 4 live testing is likewise deferred. Prompt defenses and exact citation validation cannot guarantee every generated claim is supported or prevent every prompt injection. Confidence gates are uncalibrated baseline heuristics; real-model relevance/grounding evaluation remains future work.
- Indexing is synchronous and calls the provider per pending chunk. A failed batch stores no new vectors but already completed provider requests may still incur costs. Exact search and synchronous indexing will need revisiting for larger datasets; no ANN index or background worker is included.
- JDK 21 is now installed and machine-level `JAVA_HOME` and PATH are configured correctly. This existing Codex session retained the old environment; validation loaded the installed `JAVA_HOME` into the process explicitly. Open a fresh PowerShell window or restart Codex to inherit the updated environment. The earlier portable tooling/cache remains outside the repository at `D:\Projects\.bootstrap-tools`.

## Known Future Work

Not part of Phases 1–5:

- café inventory management
- table management
- game recommendations
- physical robot hardware
- computer vision
- board-state recognition
- deterministic game-rules engine
- staff escalation
- analytics
- polished production café UI

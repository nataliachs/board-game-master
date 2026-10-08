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

Milestones 0–8 are implemented and validated with provider doubles. Sessions, progressive teaching,
bounded conversational Q&A, guided setup with teaching resume, optional speech and the thin
browser demo are implemented. Live speech quality and broader RAG evaluation remain unverified.

## Current Milestone

**Milestone 8 — Voice + Minimal Demo UI (implemented; automated validation complete)**

Milestone 8 adds optional voice and the browser demo using existing backend services. The user completed a limited live OpenAI Lantern Trail walkthrough
on 2026-10-08, exercising Milestones 3, 4 and 6. Broader live-model quality evaluation remains
deferred; the original automated validation history below is preserved.

## Milestones

- [x] Milestone 0 — Project Bootstrap
- [x] Milestone 1 — Game + GameVersion Domain
- [x] Milestone 2 — Rule Documents + Ingestion
- [x] Milestone 3 — Embeddings + Vector Search
- [x] Milestone 4 — RAG Question Answering
- [x] Milestone 5 — Production-Quality RAG
- [x] Milestone 6 — Game Sessions + Teaching Flow
- [x] Milestone 7 — Guided Setup Integrated with Teaching
- [x] Milestone 8 — Voice + Minimal Demo UI

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

Status: Complete and validated with provider doubles; a limited user-run live OpenAI demo passed on 2026-10-08. Broader live quality evaluation remains deferred.

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

Status: Complete and validated with deterministic provider doubles. Live-model quality remains unverified.

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

Status: Complete and validated with provider doubles; the user-run live teaching/session walkthrough passed on 2026-10-08. Setup integration remains Milestone 7.

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

Status: Complete and validated with provider doubles; setup-specific live provider/physical walkthroughs remain unverified.

Implemented:

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

Status: Implemented and validated with provider doubles and a synthetic-microphone browser walkthrough. Live speech quality remains unverified.

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

Milestone 8 is implemented on the existing `milestone-8-voice-demo-ui` branch. Work remains
unstaged and uncommitted. No paid API calls, commit, push or branch change were performed.
The user's private `.env` and existing Compose database were not read or changed.

- Optional independently configured STT/TTS interfaces and OpenAI HTTP adapters default to disabled. Multipart/JSON provider contracts, bounded responses, timeouts, safe failures and empty transcription are covered by fixture tests.
- The multipart session voice endpoint validates type/signature/size, preserves recognized text if answering fails and invokes the existing version-pinned question pipeline. Revision guards reject navigation races. TTS failure preserves the stored text/citations and returns a structured audio error; raw and generated audio are not persisted.
- A thin Thymeleaf browser demo loads catalog games, defaults to STANDARD, supports player count, progressive lesson/setup controls, same-session PLAYING, text questions, citations, bounded history, read-only review and completion. No frontend workflow or RAG engine was added.
- Push-to-talk requests the microphone on user action, stops tracks on release/cancellation and provides a file-upload alternative. The UI displays transcription, playable MP3 metadata/controls and understandable errors, with independently usable text. Spoken answers are disclosed as AI-generated.
- User-requested spoken teaching/setup guidance also reuses the TTS adapter. Start-screen/session controls enable narration of the first and subsequent lessons/steps, with pause/stop/replay. The backend selects authored pinned content and segments long explanations without changing progress/history; frontend preferences suppress duplicate automatic narration after refresh/questions/review. STT and RAG are not required for authored narration.
- README, architecture, OpenAPI description and `.env.example` now describe implemented Milestone 8 behavior and manual demonstrations. Private keys remain server-side. No new database migration or changes to V1–V7 were needed.
- Automated checks and browser evidence are recorded under Milestone 8 validation below. The original user-run live OpenAI walkthrough is preserved verbatim, including its remaining evaluation limitations. No future work was implemented.

### Follow-up: natural clarification retrieval

- User-run Ticket to Ride USA testing found that "Is that okay if I just keep 2 tickets?" returned LOW, while an explicit initial-setup question returned a supported MEDIUM answer. This suggests a contextual retrieval limitation; the precise original fallback branch was not captured.
- Retrieval now includes bounded active setup instructions and lesson explanations alongside titles and the latest user question. The original question remains intact; Unicode-safe per-field and overall budgets apply. No extra model call, threshold reduction or change to evidence/citation validation was introduced.
- Regression tests cover natural wording, teaching-only context, long/Unicode cues and a provider-double pgvector session journey with preserved setup progress and citations. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed: 220 unit tests and 105 isolated integration tests (325 total; zero failures, errors or skips), plus executable JAR assembly. `git diff --check` passed. No paid API calls, commit or push were performed.
- Live OpenAI retesting of this change remains pending. Deterministic fixtures establish context delivery and workflow behavior, not real embedding relevance or improved answer accuracy. Previously recorded live results and broader evaluation limitations remain unchanged.

### Follow-up: answer rejection diagnostics

- The user's subsequent live Ticket to Ride test still returned LOW at "Choose your starting tickets". Shared logs show 16 candidates, 13 below threshold, three returned matches and an LLM call; they do not identify the selected passages or precise fallback branch. The natural-follow-up issue remains unresolved, and no model-quality improvement is claimed.
- Added INFO diagnostics identifying retrieved chunk/document IDs, bounded single-line section titles, pages, similarity, prompt inclusion and the minimum answer evidence score. Each fallback now identifies its rejection reason and offending citation ID when applicable. No questions, rule bodies, generated answers/quotes, credentials or audio are logged by the new diagnostics. No models, thresholds, response contracts or evidence gates changed.
- Tests exercise rejection reasons, prompt-budget exclusion and metadata-only logging, including sanitized/truncated titles. On 2026-10-08, `gradlew.bat test bootJar --no-daemon --console=plain` passed all 222 unit tests (zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. The 105 integration tests passed in the preceding context-improvement check and were not rerun for this diagnostics-only change. No paid API calls, commit or push were performed; historical live walkthrough results and remaining evaluation limitations are preserved. The next user-run request must capture the new logs to determine the live failure cause.

### Follow-up: bounded quotation correction

- User-supplied live diagnostics on 2026-10-08 identified `QUOTE_MISMATCH` for the Draw Tickets chunk. The Setting up the Game passage was also retrieved (similarity 0.563942, included in prompt). This confirms the observed rejection was citation validation, not absence of the setup passage. The generated quote was not logged, so its precise textual error remains unknown.
- Strengthened citation instructions: copy contiguous exact quotes from the matching excerpt, and distinguish active setup from later turns using session context. An otherwise valid answer with a quote mismatch now permits one correction call using unchanged serialized evidence/context and feedback containing the rejected chunk ID, never the rejected answer/quote. Full validation applies again; no third call, relaxed thresholds or automatic quote substitution.
- Added provider-double tests for successful correction, repeated mismatch and other rejected corrections, no retry for non-quote failures, provider-error propagation, unchanged evidence budget, and a PostgreSQL session test for a single final persisted exchange with unchanged setup progress and no partial history on correction failure. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 230 unit tests and 106 isolated integration tests (336 total, zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. Live accuracy of this correction remains unverified; broader evaluation and previously recorded walkthrough results are preserved.
- No paid API calls, commit, push, private environment access or user-database modifications were performed. Live correction can add one paid LLM request and its configured per-call timeout; retrieval/indexing are not repeated.

### Follow-up: controlled local quotation comparison

- User-supplied logs from 2026-10-08 show three separate requests: at 11:03 UTC a quote correction was accepted; at 11:04:12 a model-insufficient-evidence fallback occurred; at 11:04:49 a quote mismatch persisted through the second attempt. Setup evidence was included in all three. Questions were not logged, so exact mapping to the user's five-ticket question is unknown. One accepted correction does not establish general answer quality.
- Added a dedicated quotation-comparison logger, OFF by default and enabled locally with `ANSWER_QUOTE_DIAGNOSTICS_LEVEL=DEBUG`. It captures the rejected quote and cited passage as escaped JSON, attempt/source IDs, original content length/truncation and other prompt chunk IDs containing the exact quote. Passage output is bounded at 8000 UTF-16 characters with Unicode-safe truncation. Existing INFO logs omit content. No question/full answer or credentials are added to these diagnostics.
- README and `.env.example` explain temporary opt-in, restart, single reproduction and switching OFF again; captures contain rule text and should remain outside Git. The user's private environment was not accessed or changed. Tests use fictional text and cover disabled logging, exact escaped text, wrong-source identification and bounded Unicode output. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 233 unit tests and 106 isolated integration tests (339 total; zero failures/errors/skips) and built the executable JAR; `git diff --check` passed.
- Citation checks, prompt behavior, retry bounds, models and thresholds are unchanged. No paid API calls, commit, push or user-database modifications were performed. Precise mismatch text and supported-deduction quality still require user-run capture/evaluation; previous walkthrough records and broader limitations are preserved.

### Follow-up: whitespace-tolerant source quote matching

- User-supplied opt-in diagnostics for request `68440d48-811b-4691-bd82-8be945bd5ef3` established that attempt 1 inserted ellipses between omitted sentences, while attempt 2 preserved the cited Draw Tickets wording but flattened paragraph breaks to spaces. The latter was rejected solely by the strict substring check. These findings do not verify the unseen generated answer's setup-versus-gameplay interpretation.
- Citation validation now tolerates whitespace-run differences only within the cited prompt chunk and returns the original contiguous source span. Non-whitespace text remains literal; ellipses, omitted/changed words, changed numbers/punctuation/case/order and wrong source IDs still fail. Generated and restored quotes remain bounded at 1000 UTF-16 characters. Whitespace-only matches avoid an extra correction call. The HIGH-confidence minimum-length gate uses restored source text so model-added spaces cannot inflate it. Models, thresholds, retry cap and workflow ownership are unchanged.
- Regression tests use fictional rules to cover whitespace/Unicode, source restoration, omitted/altered text, literal regex characters, size limits and protection against model-added whitespace inflating HIGH confidence, plus session persistence of original source quotes without setup movement or a retry. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 247 unit tests and 107 isolated integration tests (354 total; zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. No paid API calls, commit, push, private environment access or user-database changes were performed. Live retesting, supported-deduction quality and broader evaluation remain pending; previous results are preserved.

### Earlier implementation record (Milestone 7)

Milestone 7 was implemented on `milestone-7-guided-setup`; the following records its original scope and validation.

- V7 adds immutable version-bound SetupFlow/SetupStep and persisted session setup progress, flow/step/return-lesson ownership constraints and setup-trigger configuration. V1–V6 migrations are unchanged.
- New sessions pin the configured setup flow. Legacy sessions can pin that version's latest setup at first explicit start; later publications/default changes never repin started setup.
- CONTINUE at an unfinished setup-trigger lesson enters SETUP and saves that same lesson without completing it. Explicit start, current, next, previous, repeat, restart and complete APIs are implemented. Completion acknowledges the last step only after prior steps and returns to TEACHING, never directly PLAYING; another Continue advances normally.
- Restart clears setup confirmations while retaining teaching progress/history. Step navigation uses locked revision checks. Questions preserve setup progress; context includes the active step as untrusted context rather than citation evidence. Provider failures and stale answers leave no partial exchange.
- Six original Catan beginner setup steps reference the physical beginner diagram. A replacement seed teaching flow has QUICK 8 / STANDARD 10 / DETAILED 11 sections. Existing sessions keep the V6 flow; user-published Catan teaching is not replaced. No commercial rulebook/diagram is bundled or automatically indexed.
- README includes provider-free Catan and Lantern Trail setup demonstrations, revision handling and optional setup Q&A. No new environment variables, dependency/provider changes or private `.env` access were needed.
- Full validation passed 279 tests (192 unit, 87 isolated PostgreSQL/HTTP integration), zero failures/errors/skips, and built the executable JAR. Existing live OpenAI evidence is preserved verbatim below; no new paid calls or user-database operations were performed.

### Earlier implementation record (Milestones 0–6)


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

- Version/profile-scoped language, source type, document and explicit expansion-document filters applied before candidate selection.
- Configurable top-K, similarity threshold, bounded candidate pool, conservative duplicate suppression and replaceable validated reranking boundary.
- Search diagnostics expose stage counts, effective filters/thresholds, timing and candidate-limit saturation without logging text.
- HIGH answer confidence additionally requires complete strong-evidence citation coverage and meaningful quote lengths.
- Checked-in original Catan question/corpus evaluation fixtures, deterministic retrieval-only metrics and isolated regression tests.

- Flyway V6 adds immutable version-bound TeachingFlow/TeachingSection data, persistent GameSession and ConversationMessage tables, ownership constraints, revision and history indexes.
- Session creation resolves and pins the active default GameVersion and latest teaching flow, validates player count, defaults depth to STANDARD and starts TEACHING at the first applicable lesson.
- Progressive CONTINUE/PREVIOUS/REPEAT/EXPLAIN_AGAIN controls persist the current/completed lessons. Explain-again reuses authored examples/tips without AI generation. Final CONTINUE enters PLAYING in the same session; explicit completion records COMPLETED and endedAt.
- SessionContextService owns pinned game/edition/language, player/depth/stage/lesson and bounded recent conversation context. Generic grounded answering accepts the pinned ruleset, uses recent-topic retrieval cues, verifies new evidence/citations and preserves lesson state.
- Atomic USER/ASSISTANT exchanges retain answer provenance. Provider failures leave no partial history. Revision checks reject duplicate navigation and stale answers after concurrent session changes, without holding a transaction across provider calls.
- Reference Catan data is an original introductory overview with seven QUICK, nine STANDARD and ten DETAILED lessons. No commercial rulebook is imported; teaching text is not automatically indexed or used as citation evidence. Setup remains explicitly deferred.
- README documents provider-free lesson demonstration, optional paid Q&A, configuration, flow publication, history and completion.

At the original Milestone 6 implementation, work was uncommitted on `milestone-6-session-management`
and Milestone 7 was unstarted. The limited live OpenAI walkthrough below
supplements the original deferred-testing record for Milestones 3 and 4; broader quality
evaluation remains deferred and this implementation makes no general live-model quality claims.

## Major Architectural Decisions

Teaching/session decisions are implemented in Milestone 6, setup in Milestone 7, and voice plus
the thin browser demo in Milestone 8.
- Setup progress is independent of conversation: one all-depth teaching trigger, a version-bound pinned setup flow, explicit per-step confirmation and a saved teaching return point. Completion returns to TEACHING before readiness; shared engines contain no Catan-specific assumptions.
- Modular monolith, organized by feature.
- Game identifies the logical board game; GameVersion identifies its edition/language/ruleset.
- Customers normally select Game only; the backend resolves a default GameVersion and pins it to the session.
- Catan is the first reference dataset; shared engines are game-independent.
- Implement only the requested milestone; stop before the next.
- Root package is `com.boardgamemaster`; game, rules, AI, retrieval, sessions, teaching, setup, voice and UI feature packages are implemented. The gamemaster placeholder remains reserved.
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
- Ingestion uses validated `boardgame.rules.*` / `RULE_*` settings: 1200-character chunks, 200-character overlap budget, 1 MiB input limit and 10000 chunks by default. Servlet file/request limits remain 10 MiB. Retrieval top-K, similarity threshold, candidate limit and duplicate overlap are active; legacy `RAG_CHUNK_*` proposals remain inactive.
- Metadata requires a source reference and document version; language must match the selected version. Inactive versions may be prepared. Duplicate imports create independent documents. URLs are metadata only; uploaded basenames are sanitized and no server filesystem paths are read.
- PDF extraction, document edit/delete, deduplication, background ingestion and full Markdown parsing are deferred. No copyrighted commercial rule source is bundled. Original demo text is CC0 and should be imported into a separate demo game/version.
- Embeddings use a separate vector table keyed by chunk/provider/model/dimensions; a composite FK preserves version ownership. Changing the active profile requires explicit reindexing and cannot mix incompatible vector spaces.
- Exact cosine candidate selection is intentional at this scale. A B-tree index supports version/profile filtering; a materialized candidate query isolates dimensions before distance evaluation. Metadata filters precede the bounded candidate limit, then thresholding, duplicate suppression and a replaceable reranker precede final top-K. No ANN index is introduced.
- Provider calls happen outside database transactions. A short locked transaction persists the complete generated batch atomically and handles concurrent duplicate indexing idempotently. Concurrent callers can still incur duplicate provider costs before persistence.
- Indexing is explicit and synchronous, bounded by document chunk count and per-input characters. Existing imports are preserved and remain unindexed until requested. Ingestion does not call a provider.
- Embedding configuration defaults to disabled; blank legacy provider/model/dimensions values retain safe defaults. An enabled provider requires a key. OpenAI HTTP requests have a fixed endpoint, no redirect following and bounded timeouts; errors never include upstream bodies or credentials.
- The retrieval endpoint returns chunks, source metadata and cosine scores only. The separate question endpoint adds evidence-grounded generation and baseline confidence; similarity scores are not calibrated factual probabilities.
- Stateless questions resolve the game's active default once per request. Session questions use the pinned version and bounded context from Milestone 6. All evidence/citations remain version-scoped; no dependency changes are required. V6 adds session/teaching persistence without changing V1–V5.
- The prompt builder keeps trusted instructions separate from JSON-encoded untrusted questions/documents. It requests evidence-only answers, exact supporting quotes and explicit insufficiency; bounded whole chunks preserve retrieval order.
- LLM-generated citation IDs and quotes are checked against the exact prompt evidence. Public source metadata always comes from storage. Unknown/excluded sources, invented quotes, duplicate citations, weak cited evidence or model refusal produce the fixed LOW fallback with no sources.
- `boardgame.answer.*` gates default to 0.5 minimum and 0.8 HIGH. Generation requires usable evidence; accepted confidence uses the weakest cited score plus model-reported sufficiency. HIGH additionally requires all strong supplied candidates to be cited and each quote to reach a configurable minimum length (12 by default). These remain heuristics, not validated probabilities.
- The opt-in LLM adapter uses Responses strict structured outputs, temperature 0, bounded tokens, `store=false`, fixed HTTPS, no tools/redirects/retries and safe errors. Both providers default to disabled. External calls run outside DB transactions; timing logs omit text/keys. Legacy `AI_*` settings remain reserved.
- Expansion selection uses owned EXPANSION document IDs, excluded by default. Filters intersect within a selected version and never change its edition. A persisted session expansion selection remains unimplemented. Language defaults to the version's normalized language; mismatches return empty results without translation.
- Rerankers may only permute the original candidates; invalid/altered/foreign candidates and errors fail safely. The default preserves cosine ordering and makes no paid call. Candidate limits bound cost/work and can truncate deduplicated recall; diagnostics explicitly flag saturation.
- Duplicate suppression preserves source metadata and never deletes imports. Exact normalized content collapses only within matching version/language/type/document-version boundaries; near-contained overlap additionally requires the same document and the retained passage to contain the shorter candidate's source range. A longer passage is never dropped in favor of a shorter one. Distinct variants and source types remain separate.

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

### Milestone 8

Spoken-guidance follow-up on 2026-10-08: implemented at the user's request within Milestone 8.
The original 316-test implementation record below is retained.

- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS: 322 tests (218 unit, 104 isolated PostgreSQL/HTTP integration), zero failures/errors/skips; executable JAR built. Both browser scripts passed JavaScript syntax checks.
- New coverage verifies pinned authored lesson narration, shared Explain again text, setup instructions/notes/warnings, long-segment bounds and Unicode preservation, one synthesis call per requested segment, no database transaction during TTS, unchanged session/history, safe provider failures, invalid/stale requests and discarded audio after concurrent navigation.
- Headless Edge/Playwright with a disposable database and fake providers passed first-lesson narration after start-screen opt-in, decoded synthetic silent MP3 playback, Stop/Replay and current-page audio reuse, unchanged session state, refresh/review suppression, Explain again, setup/Repeat narration, opt-out and mobile layout. No JavaScript page errors occurred. The browser fixture substitutes a locally constructed silent MP3 for fake provider audio; this verifies playback mechanics, not real TTS quality or human-like speech. The mobile screenshot was visually inspected.
- No paid API calls, private `.env` reads, user database changes, staging, commits, pushes or branch changes. The temporary preview process/database were removed after validation. Prior live OpenAI observations are preserved verbatim; live narration/STT quality and the broader evaluation limitations remain unverified.

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- `gradlew.bat check bootJar --no-daemon --console=plain` PASS: all 316 tests (217 unit, 99 isolated PostgreSQL/HTTP integration), zero failures/errors/skips; executable JAR built. Provider requests use local HTTP fixtures or doubles; Testcontainers databases are separate from the user's Compose database.
- Coverage includes audio MIME/signature/size checks, binary defensive copies, multipart STT and JSON/MP3 TTS contracts, disabled providers, redacted configuration/errors, response bounds, malformed/empty responses, timeouts/rate limits, existing cited session answers, TTS partial failure, preserved transcription on LLM failure, unchanged progress/history on rejected requests, completed/missing sessions and setup changes during transcription. Tests verify that speech calls do not hold database transactions.
- Real HTTP/Thymeleaf tests cover backend-loaded games, STANDARD selection, provider-free teaching, generic teaching/setup/resume/playing/completion, read-only review, citations/history, escaping, stale form submissions, safe errors and page headers. Initial focused checks exposed a reserved Thymeleaf variable, test-fixture mistakes and URL session rewriting; these were corrected before the passing full suite.
- Headless Edge via Playwright passed a desktop/mobile Lantern Trail journey with a synthetic microphone, fake STT/TTS/embedding/LLM providers and an isolated PostgreSQL container. Verified push-to-talk upload, displayed transcription, saved cited answer/history, audio Blob controls, unchanged active setup step, resumed teaching, PLAYING, non-mutating review and retained history after completion. No JavaScript page errors occurred. Desktop/mobile screenshots were visually inspected; browser checks required execution outside the sandbox.
- Limitations: the synthesized MP3 in this browser fixture is header-only test data, so the check verifies audio transport/controls, not successful decoded playback or intelligible speech. Human microphone permission/device behavior, real STT accuracy and live TTS quality were not tested. No paid calls were made. Full-codec validation, server duration enforcement, production authentication and retention policies remain outside this local demo. The prior live OpenAI walkthrough and broader evaluation limitations below remain unchanged.
- No staging, commit, push or branch change. V1–V7, existing private configuration and user database were preserved. The temporary preview process and its disposable Testcontainers database were removed after validation; ignored check scripts/logs/screenshots remain under `build/`.

### Milestone 7

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Compilation and focused domain/session integration checks passed before the full suite.
- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS: all 279 tests (192 unit, 87 isolated PostgreSQL/pgvector integration), zero failures/errors/skips; executable JAR built. AI calls use doubles, without real keys or paid calls. Testcontainers databases are separate from the user's Compose database.
- Coverage includes generic and seeded Catan journeys; all depths; explicit/triggered setup; current/next/previous/repeat/restart/complete; first/last/single-step boundaries; saved teaching resume; readiness after further teaching; history and progress preservation during Q&A; evidence/context isolation and prompt budgets; missing/invalid configuration; immutable flow/version pins; provider failure; duplicate-action and restart-versus-question races; transaction rollback; database ownership; V6 upgrade/session/history/custom-publication preservation and repeatable migrations.
- Existing Milestone 0–6 checks, including deterministic retrieval evaluation and earlier migration/data-preservation tests, pass. Original live OpenAI walkthrough text is unchanged.
- Limitations: no live setup-specific OpenAI or physical-board walkthrough was performed. Catan guidance is an original concise beginner configuration requiring the physical rulebook diagram, not a complete rulebook or automatic board verification. Authored setup is not automatically indexed as evidence. Broader live grounding, confidence calibration, multi-game/adversarial/provider-failure evaluation remain deferred as recorded below. Legacy flows without setup triggers keep their earlier readiness behavior.
- No staging, commit, push or branch change. Milestone 8 remains unstarted: no speech adapters, audio endpoints, templates or browser UI.

### User-run live OpenAI walkthrough — 2026-10-08

Evidence: Swagger response bodies supplied by the user in this conversation, including the
attached final history response. These are user-run manual observations, not agent-executed
provider tests or an automated benchmark. The user configured the project for OpenAI embeddings
(`text-embedding-3-small`, 1536 dimensions) and generation (`gpt-4.1-mini`); the responses do not
independently attest the running model identifiers. No API key was included in the evidence or
read/copied into this documentation.

- Used the separate Demo Game and original CC0 Lantern Trail rules, not Catan. Successful cited answers demonstrate usable indexed embeddings and live retrieval/generation for this narrow sample. A separate indexing response or retrieval-only response was not supplied, so no indexing counts or standalone retrieval measurements are claimed.
- Stateless supported question: reaching the last space correctly returned taking a spare marker and returning the token to the first space, with an exact quote from Taking a turn, page 2. Confidence was MEDIUM; cited cosine similarity was approximately 0.5714.
- Stateless unsupported question about buying a teleport card returned the fixed insufficient-evidence answer, LOW and no sources. The response does not establish whether retrieval gates or the model's insufficiency decision caused the fallback.
- Published a two-section STANDARD-compatible teaching flow and created an ACTIVE / TEACHING session at TAKING_A_TURN with revision 0. The session retained the selected game version and published flow identifiers throughout the walkthrough.
- Asked the supported question during teaching, then the follow-up "After taking that marker, where do I put my token?" Both returned correct cited answers with MEDIUM confidence (similarities approximately 0.7072 and 0.7734). GET session confirmed the lesson remained TAKING_A_TURN, completedSections stayed empty and revision advanced to 2. This is one successful contextual follow-up, not proof that history was necessary to answer or that arbitrary follow-ups work.
- History returned four ordered USER/ASSISTANT messages with stored answer citations. CONTINUE at revision 2 completed the first lesson and moved to ENDING_THE_DEMO at revision 3. CONTINUE at revision 3 completed teaching and entered ACTIVE / PLAYING at revision 4 with readyToPlay=true, retaining the same session ID. Guided setup was explicitly absent.
- Gameplay question about spare markers running out correctly returned finishing the current turn, ending the demo and counting collected markers, citing Ending the demo, page 2. Confidence was MEDIUM, cited similarity approximately 0.7557 and returned revision 5. A separate post-question GET confirming PLAYING was not supplied.
- Explicit completion at revision 5 returned COMPLETED stage/status, revision 6, a populated endedAt and both completed section keys. A subsequent question returned INVALID_SESSION_ACTION / "This session is completed" as expected; the supplied body establishes the application error, while the HTTP status was not separately pasted.
- Final history retained exactly six ordered messages (sequence numbers 1–6): three USER questions and three ASSISTANT answers, with their quotes/source metadata preserved. The rejected post-completion question added no history entry.
- This supersedes blanket "all live testing deferred" statements for these exercised paths only. Broader Catan/multi-game live evaluation, long or ambiguous conversation quality, adversarial prompts, provider failure/rate-limit behavior, threshold calibration and factuality/hallucination measurement remain unverified. Previous automated/provider-double results and historical deferral statements remain unchanged below.
- Documentation-only recording: no source/configuration edits, paid calls, database operations, staging, commit, push or branch change performed by the agent. Application tests were not rerun for this documentation-only update; the 254-test result below belongs to Milestone 6 implementation validation. Documentation whitespace check passed.

### Milestone 6

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Initial Java compilation passed. The first full check passed all 175 unit tests and 72/73 integration tests; its only failure was a historical Milestone 4 OpenAPI assertion that session endpoints must be absent. That assertion was updated for Milestone 6 while preserving the stateless question contract. Additional context-budget, Unicode, simultaneous-question and database rollback checks were then added. The subsequent full `check bootJar` passed all 254 tests. Final user-facing wording was also revalidated with the same full command.
- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 254 tests passed: 179 unit and 75 isolated PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully. Existing Milestone 0–5 checks remain passing, including deterministic retrieval evaluation.
- Unit coverage includes ordered immutable depth-specific content, section validation, persisted-state transitions, completion, first/last navigation boundaries, nonadvancing controls, wrong-flow rejection, bounded context defaults/limits, whole-message selection, contextual retrieval bounds/Unicode and structured untrusted context that cannot become evidence. Prompt budgets remove older context before sacrificing retrieved evidence.
- Seventeen new PostgreSQL/real-HTTP integration cases cover session creation/default depth/player validation, seeded Catan depth selection, generic flow publication, immutable flow/default pinning, progressive navigation and explain-again examples, saved citations and contextual follow-ups, question-preserved lessons/resumption, cross-session isolation and history cursors, empty/weak evidence fallback, safe provider timeout with no partial exchange, same-session PLAYING/COMPLETED transitions, concurrent navigation/questions, stale answers and atomic rollback after assistant-message insertion failure.
- Migration checks cover fresh V6 startup, repeatability, flow/version/section/lifecycle constraints and V5-to-V6 upgrade preserving catalog rows, imported text, chunks and existing vectors. Earlier V1/V3/V4-to-current migration checks are retained and updated for the added migration.
- Tests use local provider doubles and isolated Testcontainers databases; no paid calls or real API keys. Existing Compose data/services and private `.env` were not read, changed or migrated. Restart the local app to apply V6 for manual use. Provider-free teaching and optional live Q&A instructions are in README.
- Live OpenAI testing for Milestones 3 and 4 remains explicitly deferred. Contextual follow-up quality, real-model grounding and confidence calibration remain unverified. Explain-again is deterministic authored content with examples/tips, not a live LLM rewrite. Reference lessons are introductory and do not execute/verify physical setup.
- `git diff --check` and new-source whitespace checks — PASS. AGENTS.md, PROJECT_SPEC.md, build dependencies, provider adapters and V1–V5 migrations are unchanged. Optional SESSION_* defaults were added to `.env.example` and application configuration; private `.env` remains untouched.
- Work remains unstaged/uncommitted on `milestone-6-session-management`. No commit, push or branch change was performed. Milestone 7 remains unstarted: no SetupFlow execution, setup triggers/steps, interruption/resume integration, voice or browser UI were added.

### Milestone 5

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- The unchanged unit suite passed first. The first expanded run found a constructor-binding issue in the new properties records; explicit canonical constructor binding corrected it. A subsequent full `check bootJar` passed 217 tests (159 unit, 58 integration). Final review tightened duplicate containment to retain longer passages and added candidate-saturation regression coverage; the final full results below supersede intermediate checks.
- Final `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 218 tests passed: 160 unit and 58 PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully; existing Milestone 0–4 checks continue to pass.
- Evaluation uses `src/test/resources/evaluation/catan-rule-questions.json`: original narrow fact paraphrases, six supported questions and two unsupported questions with hand-assigned vectors. It imports duplicate sources and foreign game/edition decoys, checks expected topics/sections and never calls the LLM. Final recorded results: Recall@3 = 1.0, MRR@3 = 1.0, unsupported abstentions = 2/2. The generated per-question report is `build/reports/retrieval-evaluation.json`.
- Unit coverage includes pipeline order, top-K after duplicate suppression/reranking, safe rejection of missing/duplicate/foreign/altered reranker candidates and failures, nonfinite/out-of-range thresholds, normalized bounded immutable filters, conservative contained overlap and conflicting variants, candidate saturation, startup validation/legacy blank settings, uncited strong evidence and trivial-quote confidence caps.
- PostgreSQL/HTTP coverage includes source-type/document filtering before candidate selection, default language and cross-language ruleset isolation, explicit expansion opt-in, foreign/non-expansion selections failing before provider calls, duplicate-heavy top-K recovery, diagnostics, question filter propagation, weak-evidence fallback without LLM calls and meaningful invalid-filter/threshold/ownership responses.
- These are deterministic pipeline results, not measurements of real embedding relevance, confidence calibration, answer factuality or hallucination rate. Live OpenAI testing for Milestones 3 and 4 remains deferred, as requested.
- Tests use local provider doubles and isolated Testcontainers databases. No real API keys, paid calls, existing Compose data, `.env` edits or local service changes are required. No Milestone 6 work, commit, push, staging or branch change is performed.
- `git diff --check` and source/fixture whitespace checks — PASS. AGENTS.md, PROJECT_SPEC.md, dependencies and migrations are unchanged; Flyway remains at V5. Changes remain unstaged and uncommitted on `milestone-5-production-quality-rag`. README and architecture documentation describe defaults, API examples, evaluation reproduction and limitations.

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

- No remaining automated Milestone 8 acceptance blockers are known. Docker/WSL is working. The limited user-run live OpenAI walkthrough above passed; broader live provider/quality evaluation and live STT/TTS quality remain deferred. Browser voice validation used a synthetic microphone and fixture audio rather than human speech or live synthesis.
- Session/teaching/setup APIs are local-development functionality without authentication. Flows are append-only and existing sessions pin their original content. Reference Catan lessons are an original introductory overview, not a complete commercial rulebook; authored reading pointers are not verified generated-answer citations. Setup confirmations are recorded, but the application cannot verify the physical board. Setup-specific live provider/physical walkthroughs remain unverified.
- Session context keeps a bounded window of whole messages; an oversized newest message can leave no retained history. Retrieval uses deterministic recent-user-topic/lesson/setup-title cues rather than model-driven rewriting. The prompt budget may further remove context to retain source evidence. Summarization, persisted expansion selection, retention/deletion policies and live follow-up quality remain unimplemented/unverified.
- Session revisions reject duplicate actions and discard stale generated answers after concurrent changes with no partial exchange. Provider requests completed before a conflict may still incur charges; no automatic retry is performed.
- Docker access and localhost checks required execution outside the sandbox. Future integration checks need access to the running Docker Linux engine.
- Catalog, rule import, indexing, retrieval and question answering are local-development functionality without authentication. PDF extraction, edit/delete APIs, full BCP 47 registry validation and later-milestone functionality remain outside this implementation. Repeat imports create additional documents; retrieval suppresses duplicates without deleting stored documents/chunks.
- Automated tests establish provider contracts and deterministic database behavior without real API keys. The user-run live demo additionally exercised narrow retrieval/generation and session paths, but is not a real-model relevance benchmark. Provider token limits can still reject text because local bounds count characters, not tokens.
- Live OpenAI testing for Milestones 3 and 4 was originally deferred; the limited walkthrough above now supplements that historical record. Broader quality evaluation remains deferred. Prompt defenses and exact citation validation cannot guarantee every generated claim is supported or prevent every prompt injection. Confidence gates remain uncalibrated heuristics; neither deterministic Milestone 5 metrics nor this small manual demo replaces systematic grounding evaluation.
- Candidate pools are bounded and can underfill top-K after duplicate suppression; diagnostics flag saturation. The default reranker preserves cosine ordering and does not claim a learned relevance improvement. Expansion selection is document-level only; language filtering does not translate or bypass version isolation. These remain local-development RAG improvements rather than a production-readiness claim.
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

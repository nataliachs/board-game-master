# Architecture

Root package: `com.boardgamemaster`. The application scans feature packages beneath it.
Milestone 0 provides `common.config` and `common.exception` infrastructure.
Milestone 1 adds `game.domain`, `game.application`, `game.infrastructure`, and `game.api`.
Milestone 2 adds the corresponding `rules` packages for source ingestion and chunk reads.

Future feature packages are reserved below; they contain documentation only:
`rag`, `session`, `gamemaster`, `setup`, and `ai`.
Add domain/application/infrastructure/api subpackages only as each milestone needs them.
No RAG, session, setup, or provider implementations exist yet.

`Game` represents a logical game; `GameVersion` owns edition, version label, language,
and default/active flags. UUID ownership is enforced by a database foreign key and scoped
repository queries. Controllers only serialize response DTOs; entities never cross HTTP.
Scalar mappings avoid lazy-loading requirements outside service transactions.
The service is transactional and domain constructors enforce metadata/player invariants.

V2 adds `games` and `game_versions`, checks for positive player limits/duration, valid
metadata, and active defaults. A partial unique index permits at most one default per game.
The unique case-insensitive version identity index begins with `game_id`, supporting both
ownership lookups and foreign-key access. The game slug has a unique index.
All default mutations first take a pessimistic write lock on the parent game, serializing
concurrent version creation/default selection. The previous flag is flushed before enabling
the replacement; a failure rolls back the whole transaction. Domain-level catalog errors
are translated by feature-scoped advice; database integrity conflicts return a safe 409.

V3 seeds only Catan metadata and one active English 5th Edition default. This is a selected
reference configuration, not a rulebook, supported rules-Q&A feature, or latest-edition claim.
Release year is unspecified; no commercial rules content is included.

V4 creates `rule_documents` and `rule_chunks`. Documents belong to GameVersion, retaining
source type, document version/language/reference, sanitized basename, timestamp, cleaned text,
and the import's effective chunk size/overlap/count. A composite document/version FK prevents
chunks from being assigned to another ruleset. Each document has unique ordered chunk indices;
chunks retain section/page metadata and exact offsets into its stored normalized text.
Game/edition metadata resolves through the owning GameVersion; no game-specific code is used.

`RuleDocumentService` accepts bytes and validated metadata without HTTP types. Its transactional
import validates version/language, processes all content, and stores document and chunks atomically.
`TextRuleProcessor` strictly decodes UTF-8 .txt/.md, cleans whitespace/line endings and splits at
headings, pages and paragraphs. Oversized paragraphs prefer sentence/word boundaries; long tokens
use surrogate-safe hard splits. Whole-part overlap stays within sections/pages and must advance
to new content. Markdown fences prevent false headings/page markers; Markdown is never executed.
Standalone `<!-- page: N -->` markers provide optional page metadata. Offsets use UTF-16 indices
with an exclusive end. This is deterministic structural chunking, not embedding-based segmentation.

`boardgame.rules.*` configuration binds `RULE_*` limits and rejects invalid settings on startup.
Imports default to 1 MiB and 10000 chunks, capped by separate 10 MiB servlet request/file limits.
DTO controllers supply multipart import and bounded metadata/chunk pages plus cleaned-source reads.
The feature's advice maps safe errors to the common DTO. URLs remain metadata; server paths are
never opened. Original CC0 demo content lives in `examples/` and is not automatically imported.
Duplicate imports create separate documents. PDF, editing/deletion, embedding columns, vector
indexes, providers, and retrieval are deferred. Milestone 3 remains unstarted.

HTTP failures share `code`, `message`, UTC `timestamp`, and request `path`.
MVC advice preserves HTTP status and framework headers; servlet dispatches use the same DTO.
Unexpected exception messages and stack traces are not sent to clients or logged.
Each request gets a generated `X-Request-ID`, also placed in the logging MDC.
Console logs use Spring Boot ECS JSON; database health details remain private.

Flyway owns schema changes; Hibernate uses `ddl-auto=validate` and open-in-view is disabled.
V1 installs pgvector; V2 creates the catalog schema, V3 seeds reference metadata, and V4 adds rules.
The database account needs extension-creation privileges for V1; production provisioning may
install the extension ahead of time. Production credentials come from environment variables.

The local profile imports optional `.env` as Java properties. Use simple `NAME=value` lines
without shell `export`, quotes, or Compose-only interpolation. Run from the repository root.
The production profile does not import `.env` and disables Swagger/OpenAPI by default.
This bootstrap has no authentication and is intended for local development.

Tests split fast MVC/error checks (`test`) from real PostgreSQL/pgvector startup and HTTP
checks (`integrationTest`). `check` requires both, so unavailable Docker cannot yield a
successful full verification. Neither suite calls paid services.

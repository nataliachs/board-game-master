# Architecture

Root package: `com.boardgamemaster`. The application scans feature packages beneath it.
Milestone 0 provides `common.config` and `common.exception` infrastructure.
Milestone 1 adds `game.domain`, `game.application`, `game.infrastructure`, and `game.api`.
Milestone 2 adds the corresponding `rules` packages for source ingestion and chunk reads.
Milestone 3 adds `ai` embedding types/adapters and `rag` indexing/retrieval application,
storage-port, PostgreSQL adapter and DTO HTTP operations.

Future feature packages are reserved below; they contain documentation only:
`session`, `gamemaster`, and `setup`.
Add domain/application/infrastructure/api subpackages only as each milestone needs them.
No answer-generation, session, teaching, setup or speech implementations exist yet.

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
Duplicate imports create separate documents. PDF and document editing/deletion remain deferred.

V5 adds `rule_chunk_embeddings`, separate from the immutable source chunks. A composite
chunk/version FK enforces ownership. The primary key identifies chunk + provider + model +
dimensions, allowing vector spaces to coexist. pgvector dimensions must match stored metadata;
zero vectors are rejected. Existing imports remain intact and start unindexed after migration.
The scalar JPA model does not expose vector SQL types; `RuleVectorStore` is the application port
and `PgVectorRuleStore` owns parameterized SQL and vector serialization using JdbcTemplate.

`EmbeddingClient` returns validated immutable, unit-normalized vectors behind a provider-neutral interface.
The OpenAI HTTP adapter uses the documented embeddings endpoint, a fixed HTTPS URI, no redirects,
connect/request timeouts, float responses and explicit dimensions. Provider credentials and
responses never appear in public errors or application logs. The default disabled adapter permits
ordinary startup/import without credentials; explicit indexing/search fails safely with 503.
Blank legacy EMBEDDING_* settings are treated as disabled/default values where appropriate.

`RuleEmbeddingService` validates ownership and limits, generates only missing vectors, then
stores the whole pending batch atomically. Provider calls happen outside database transactions;
the short write transaction locks the document and uses conflict-safe inserts. Repeat indexing
is idempotent for the active profile. Concurrent requests may both pay for generation, but only
one vector per chunk/profile is stored. Existing vectors remain usable after a failed new batch.
Indexing is synchronous and defaults to 1000 chunks/document; background jobs remain deferred.

`RuleRetrievalService` validates the version and input, embeds the query, and searches only the
active provider/model/dimension profile within that version. Empty indexes return no matches
without a provider request. Exact cosine ordering uses deterministic document/chunk tie-breaks.
The filtered candidate CTE is materialized so vectors of other dimensions are never evaluated.
A B-tree index supports version/profile filtering; no ANN index is used at MVP scale, preserving
exact recall and allowing configurable dimensions (1–2000). HNSW/IVFFlat requires later scale and
recall evidence. There is no threshold, reranking, deduplication, confidence label or generated
answer yet. Basic top-K is configurable (1–50); advanced retrieval improvements remain Milestone 5.

REST operations expose explicit document indexing/status and version-scoped source matches,
including document/version IDs, source type/version/language/reference, section/page, offsets
and cosine similarity. Logs contain timing/IDs/counts, never rule/query text or API keys.
Milestone 4 remains unstarted.

HTTP failures share `code`, `message`, UTC `timestamp`, and request `path`.
MVC advice preserves HTTP status and framework headers; servlet dispatches use the same DTO.
Unexpected exception messages and stack traces are not sent to clients or logged.
Each request gets a generated `X-Request-ID`, also placed in the logging MDC.
Console logs use Spring Boot ECS JSON; database health details remain private.

Flyway owns schema changes; Hibernate uses `ddl-auto=validate` and open-in-view is disabled.
V1 installs pgvector; V2 creates the catalog schema, V3 seeds reference metadata, V4 adds rules,
and V5 adds embeddings and their ownership/profile indexes.
The database account needs extension-creation privileges for V1; production provisioning may
install the extension ahead of time. Production credentials come from environment variables.

The local profile imports optional `.env` as Java properties. Use simple `NAME=value` lines
without shell `export`, quotes, or Compose-only interpolation. Run from the repository root.
The production profile does not import `.env` and disables Swagger/OpenAPI by default.
This bootstrap has no authentication and is intended for local development.

Tests split fast MVC/error checks (`test`) from real PostgreSQL/pgvector startup and HTTP
checks (`integrationTest`). `check` requires both, so unavailable Docker cannot yield a
successful full verification. Neither suite calls paid services.

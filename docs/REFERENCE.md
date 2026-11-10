# Reference: configuration and APIs

The detailed companion to the [README](../README.md): every setting, the REST APIs with
PowerShell examples, the retrieval and answer pipeline, and the voice endpoints. For the design
behind them, see [ARCHITECTURE.md](ARCHITECTURE.md); for dated test results, see
[DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md).

The API examples use **Lantern Trail**, an original fictional sample game in
[`examples/`](../examples/) (CC0), so they never depend on a commercial rulebook. The browser
admin area at `/admin` does the same content tasks without any API calls; see the
[admin guide](ADMIN_GUIDE.md).

## Configuration

The default `local` profile reads `.env` automatically; no shell import is needed.
Use simple unquoted `NAME=value` entries (Java properties syntax), with no `export` or
variable interpolation in `.env`. Existing environment variables override file values.
The database binds only to `127.0.0.1`; its named volume persists across restarts.
Use `docker compose stop` to stop it while retaining data. Changing credentials in
`.env` does not change credentials in an already initialized PostgreSQL volume.

Active configuration:

| Variable | Default / purpose |
| --- | --- |
| `POSTGRES_DB` | `board_game_master`; database created on first container startup |
| `POSTGRES_USER` | `board_game_master`; local database owner |
| `POSTGRES_PASSWORD` | Required local secret; also the application's default DB password |
| `POSTGRES_PORT` | `5432`; localhost port used by Compose and the local app |
| `SERVER_PORT` | `8080`; application HTTP port |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Optional local overrides; all three required for `prod` |
| `SPRING_PROFILES_ACTIVE` | Default is `local`; set `prod` for external DB configuration |
| `RULE_CHUNK_SIZE` | `1200`; maximum chunk length, 64–16000 UTF-16 characters |
| `RULE_CHUNK_OVERLAP` | `200`; whole trailing paragraph/sentence overlap budget, 0 to size minus 1 |
| `RULE_MAX_BYTES` | `1048576` (1 MiB); import byte limit, at most 10 MiB |
| `RULE_MAX_CHUNKS` | `10000`; import chunk limit, 1–100000 |
| `EMBEDDING_PROVIDER` | `disabled`; set `openai` to enable provider requests |
| `EMBEDDING_MODEL` | `text-embedding-3-small`; selected embedding model |
| `EMBEDDING_API_KEY` | Required only when `openai` is enabled; keep private |
| `EMBEDDING_DIMENSIONS` | `1536`; vector dimensions, 1–2000 |
| `EMBEDDING_TIMEOUT` | `30s`; connect/request timeout, greater than zero and at most 120s |
| `EMBEDDING_MAX_INPUT_CHARACTERS` | `4000`; maximum chunk/query UTF-16 length, 1–16000 |
| `EMBEDDING_MAX_DOCUMENT_CHUNKS` | `1000`; synchronous indexing limit, 1–10000 |
| `RAG_TOP_K` | `5`; default retrieved match count, 1–50 |
| `RAG_SIMILARITY_THRESHOLD` | `0.5`; minimum cosine similarity, finite −1 to 1; blank legacy values use the default |
| `RAG_CANDIDATE_LIMIT` | `200`; candidates considered before deduplication/reranking, 50–500 |
| `RAG_DUPLICATE_OVERLAP` | `0.85`; minimum five-word-shingle overlap for contained source ranges, 0.5–1 |
| `RAG_QUERY_REWRITE_ENABLED` | `false` locally, `true` in the `prod` profile; rewrites short session follow-ups ("how about the other players?") into standalone searches using the previous question; adds up to one LLM call using `LLM_*` settings |
| `RAG_EVIDENCE_REVIEW_ENABLED` | `false`; experimental answer-only relevance selection and cited-answer support review; adds up to two LLM calls |
| `RAG_EVIDENCE_REVIEW_CANDIDATE_FLOOR` | `0.35`; experimental pool/quote similarity floor, finite 0–1; applies only with review enabled |
| `RAG_EVIDENCE_REVIEW_CANDIDATE_COUNT` | `12`; candidate passages before relevance selection, 1–20 |
| `RAG_QUERY_DIAGNOSTICS_LEVEL` | `OFF`; local opt-in `DEBUG` logs effective search-query text and candidate metadata/scores before threshold filtering |
| `LLM_PROVIDER` | `disabled`; set `openai` to enable answer generation |
| `LLM_MODEL` | `gpt-4.1-mini` (default), or opt-in `gpt-5.6-luna`; Responses structured outputs |
| `LLM_REASONING_EFFORT` | `none` (application default); Luna also accepts `low`, `medium`, `high`, `xhigh`, `max`. Other models require `none` and omit the reasoning parameter |
| `LLM_API_KEY` | Required when the LLM is enabled; private and independent of the embedding key setting |
| `LLM_TIMEOUT` | `30s`; connect/request timeout, greater than zero and at most 120s |
| `LLM_MAX_OUTPUT_TOKENS` | `800`; output budget, 128–4096; incomplete output fails safely |
| `ANSWER_MINIMUM_EVIDENCE_SIMILARITY` | `0.5`; baseline acceptance gate for cited evidence, 0 to below the HIGH gate |
| `ANSWER_HIGH_EVIDENCE_SIMILARITY` | `0.8`; HIGH confidence gate, above the minimum and at most 1 |
| `ANSWER_MAX_CONTEXT_CHARACTERS` | `24000`; serialized question/evidence budget, 1000–160000 UTF-16 characters |
| `ANSWER_MINIMUM_QUOTE_CHARACTERS` | `12`; minimum supporting quote length for HIGH confidence, 1–1000 |
| `SESSION_RECENT_MESSAGE_LIMIT` | `8`; maximum recent messages considered for contextual Q&A, 2–50 |
| `SESSION_CONTEXT_CHARACTERS` | `8000`; whole recent-message content budget, 1000–16000 |

Speech settings are documented under [Voice](#voice) below; the PDF import settings
(`GAME_DRAFT_*`) under [deployment limits](DEPLOY_GCP.md#6-limits-on-ai-requests).
`AI_*` and `RAG_CHUNK_*` settings remain reserved.
Embedding, retrieval (`RAG_*` except `RAG_CHUNK_*`), LLM, answer and session settings in the table are active. No provider keys are needed for ingestion,
startup or automated tests; the default provider is disabled.
Active ingestion configuration uses `boardgame.rules.*` / `RULE_*`; the older `RAG_CHUNK_*`
proposals remain inactive. Invalid ingestion/embedding/retrieval/LLM/answer/session/speech limits fail startup. Add optional
overrides to your existing `.env`; do not overwrite it with the example file.
The `prod` profile does not read `.env` and disables API documentation by default.
Flyway creates the vector extension; the database migration role needs suitable privileges.

## Database migrations and tests

With the database running, `bootRun` applies Flyway migrations before Hibernate schema
validation. V1 installs pgvector; V2 creates the catalog tables and constraints;
V3 adds Catan reference metadata; V4 adds version-scoped rule documents and chunks.
Restart an already-running application to pick up the new code and migrations.
V5 adds embedding storage and its ruleset/profile index. Existing database volumes should be
retained. No rule documents or embeddings are seeded automatically; existing imports keep
their stored vectors, and unindexed documents require explicit indexing.
V6 adds sessions, conversation history and version-bound teaching, including original introductory
Catan lessons. V7 adds setup flows/steps, persisted session setup progress and a setup trigger
in a new reference teaching flow. Existing lessons, sessions, history, documents and vectors are retained.
V8 adds optional game `category` and `cover_image_url` columns. V9 replaces the Catan reference game with
Ticket to Ride: it deletes the Catan seed together with its sessions, history and any rule documents or vectors
attached to its version, then adds Ticket to Ride with lessons and guided setup. Other games are not changed.

```powershell
.\gradlew.bat test             # Domain validation and MVC/error tests; no Docker needed
.\gradlew.bat integrationTest  # Isolated pgvector PostgreSQL via Testcontainers; Docker required
.\gradlew.bat check bootJar    # Both suites and executable JAR; fails if Docker is unavailable
```

The integration suite runs real HTTP servers and isolated PostgreSQL/pgvector containers.
It verifies migrations (including upgrading a V1 database while preserving existing data),
catalog registration/read APIs, ownership, duplicates, inactive/missing defaults, player limits,
atomic and concurrent default changes, database constraints, health, Swagger, and OpenAPI.
It never silently skips missing Docker and does not use your local Compose database or `.env`.
No tests call paid AI services. Rule ingestion tests also cover cleanup, chunk boundaries,
overlap, UTF-8 validation, source offsets, page metadata, version isolation, atomic rollback,
upload limits, migration preservation and vector retrieval. See development status for current test totals.
HTML reports appear under `build/reports/tests/test` and `build/reports/tests/integrationTest`.

Manual demonstration after starting the application:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-RestMethod http://localhost:8080/v3/api-docs

# Open http://localhost:8080/swagger-ui/index.html in a browser.

```

Health should contain `"status":"UP"`. Swagger documents catalog, rules, retrieval, questions,
sessions, teaching and guided setup operations. The latest full local `check bootJar` run passed
**539 tests** (429 unit and 110 integration) with mock providers and built the executable JAR.
This is local validation, separate from GitHub Actions or live-model evaluation; see
development status for the dated evidence and remaining limitations.

The [GitHub Actions workflow](https://github.com/nataliachs/board-game-master/actions/workflows/ci.yml)
runs `check bootJar` on pull requests targeting `main`,
pushes to `main`, and manual dispatch. New tests are discovered automatically; no OpenAI secrets
are required. A push to a feature branch alone does not trigger this workflow.
The badge at the top tracks `main`; it does not validate uncommitted local changes. Check the
run for your PR's latest commit before merging. The workflow definition is in
[ci.yml](../.github/workflows/ci.yml).

## Catalog API

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/games?page=0&size=20` | Stable page DTO (`items`, `page`, `size`, `totalElements`, `totalPages`), sorted by slug; size 1–100 |
| POST | `/api/v1/games` | Register metadata; 201 and Location header |
| GET | `/api/v1/games/{gameId}` | Read metadata |
| POST | `/api/v1/games/{gameId}/versions` | Register version; 201 and Location header |
| GET | `/api/v1/games/{gameId}/versions` | Read this game's versions |
| GET | `/api/v1/games/{gameId}/versions/{versionId}` | Read a version with ownership validation |
| GET | `/api/v1/games/{gameId}/default-version?playerCount=4` | Resolve the active default; player count is optional |
| PUT | `/api/v1/games/{gameId}/default-version` | Select an active, owned version using `{"gameVersionId":"..."}` |

Metadata reads include inactive games/versions for configuration visibility. Default resolution
requires an active game, an active default, and a valid player count when supplied.
Games can be registered before their versions; missing defaults return 409 rather than picking
an arbitrary version. Registering with `defaultVersion: true` replaces the previous default
atomically. Repeating PUT with the same version is idempotent. Inactive versions cannot be defaults.
Version identity is edition + versionLabel + language within a game; edition/label comparisons
are case-insensitive and language tags are stored in lowercase. Supported tag syntax includes
`en`, `id`, and `en-US`; it is a constrained syntax, not full BCP 47 registry validation.
The catalog API supplies registration/read and default selection. Editing details and hiding a
game are done in the admin area; deletion and activation APIs are not implemented.

After restarting the application, demonstrate the seeded data in PowerShell:

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/games'
$referenceId = '00000000-0000-0000-0000-000000000101'
Invoke-RestMethod "http://localhost:8080/api/v1/games/$referenceId/versions"
Invoke-RestMethod "http://localhost:8080/api/v1/games/$referenceId/default-version?playerCount=4"

# playerCount=6 returns HTTP 400 with code INVALID_PLAYER_COUNT.

```

Register another game and a default version:

```powershell
$gameBody = @{
    slug = "demo-$([guid]::NewGuid().ToString('N'))"
    name = 'Demo Game'
    minPlayers = 2
    maxPlayers = 4
    estimatedDurationMinutes = 30
    active = $true
} | ConvertTo-Json
$game = Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/v1/games' `
    -ContentType 'application/json' -Body $gameBody
$versionBody = @{
    edition = 'First Edition'
    versionLabel = 'base-game'
    language = 'en'
    active = $true
    defaultVersion = $true
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/games/$($game.id)/versions" `
    -ContentType 'application/json' -Body $versionBody
Invoke-RestMethod "http://localhost:8080/api/v1/games/$($game.id)/default-version?playerCount=2"
```

Errors retain the shared `code`, `message`, `timestamp`, and `path` fields. Invalid bodies,
player counts, and pagination return 400; unknown games or versions belonging to another
game return 404; duplicates and unavailable default configurations return 409.
Ticket to Ride's 60-minute duration is the upper end of the box estimate. The edition is a reference
configuration, not a claim to have ingested its rules. Player counts follow the 2025 rules booklet.

## Rule-document API

All routes are scoped to `/api/v1/game-versions/{versionId}/documents`.

| Method | Suffix | Behavior |
| --- | --- | --- |
| POST | (none) | Multipart `metadata` JSON and `file`; returns 201, metadata and Location |
| GET | `?page=0&size=20` | Document metadata pages, ordered by import time and ID; size 1–100 |
| GET | `/{documentId}` | Source metadata, import timestamp and effective chunk settings/count |
| GET | `/{documentId}/source` | Cleaned source text for tracing chunk offsets |
| GET | `/{documentId}/chunks?page=0&size=20` | Chunks ordered by zero-based `chunkIndex`; size 1–100 |

Import only sources you own or have permission to use. This API takes local `.txt` or `.md`
files encoded as UTF-8; the admin area additionally accepts PDFs with selectable text, which it
converts to text with a `<!-- page: N -->` marker per page. Metadata requires `name`, `sourceType` (`RULEBOOK`, `FAQ`, `ERRATA`,
`EXPANSION`), document `version`, `language`, and `sourceReference` (a descriptive source
label, local reference, or URL retained as data). Language must match the selected GameVersion.
Inactive versions can receive documents for catalog preparation. No server paths or URLs
are fetched. Files are decoded strictly; empty/binary/malformed input fails before storage.

Cleanup removes a leading BOM, normalizes line endings to LF, removes trailing spaces,
and collapses excess blank lines. Chunking follows ATX Markdown headings (`#` through `######`)
and paragraphs, then sentence/word boundaries for oversized paragraphs. Long tokens use a
Unicode-safe hard split as a fallback. Fenced code stays text. This is structural chunking,
not an AI-based semantic parser or a full Markdown renderer; Setext headings, tables and lists
receive paragraph treatment. The limit measures UTF-16 characters, not embedding tokens.

Overlap reuses whole trailing paragraphs/sentences when they fit the configured budget;
it may be smaller than requested or zero and never crosses a section or page boundary.
Optional standalone `<!-- page: N -->` markers identify positive page numbers; otherwise
`pageNumber` is null. Page markers stay in the cleaned source but are excluded from chunks.
Sections retain the nearest heading. Chunks carry document/version IDs and exact
`startOffset`/`endOffset` into cleaned source (zero-based UTF-16, exclusive end). The GameVersion
links to the owning game and edition. Imports store all chunks in one transaction.

For a safe manual demo, first register a **Demo Game** and English version using the catalog
example above, then resolve that game's default version. Do not attach fictional demo rules
to the Ticket to Ride reference version. From the repository root in PowerShell:

```powershell

# $game is the Demo Game created in the catalog example above.

$version = Invoke-RestMethod "http://localhost:8080/api/v1/games/$($game.id)/default-version"
$base = "http://localhost:8080/api/v1/game-versions/$($version.id)/documents"

# curl.exe is used explicitly to avoid the Windows PowerShell curl alias.

$importJson = curl.exe --fail-with-body --silent --show-error -X POST $base `
    -F 'metadata=@examples/original-demo-metadata.json;type=application/json' `
    -F 'file=@examples/original-demo-rules.md;type=text/markdown'
$document = $importJson | ConvertFrom-Json
Invoke-RestMethod "$base/$($document.id)"
(Invoke-RestMethod "$base/$($document.id)/chunks?size=100").items | Format-List
Invoke-RestMethod "$base/$($document.id)/source"
```

The bundled Lantern Trail document is original fictional text dedicated under CC0 1.0.
It is not a commercial rulebook. For your own permitted source, use your file path after `@`
and prepare matching JSON metadata. Keep commercial/private inputs outside Git (for example
the ignored `local-rules/` folder). Swagger also accepts the multipart upload.

Errors use the shared DTO: invalid input 400, unknown/wrong-version documents 404,
unsupported extension 415, byte/chunk limits 413, database conflicts 409. A separate servlet
limit rejects files/requests over 10 MiB; oversized requests may return `PAYLOAD_TOO_LARGE`
before application-level `DOCUMENT_TOO_LARGE` validation. Upload MIME labels are not trusted;
extension and decoded contents determine acceptance.

PDF upload and removing a source are available in the admin area, not through this API. URL
import, document editing, deduplication and asynchronous ingestion are not implemented, and
repeating an import creates a new document. When `ADMIN_PASSWORD` is set, these content-changing
requests need HTTP Basic auth as `admin` (see `AdminAccessFilter`); the `prod` profile requires it.
Asking a rule question (`POST /api/v1/games/{gameId}/questions`) needs no password; it is limited by
the paid-request caps instead.

## Embeddings and retrieval API

| Method | Path | Behavior |
| --- | --- | --- |
| GET | `/api/v1/game-versions/{versionId}/documents/{documentId}/embeddings` | Counts for the active vector profile; no provider call |
| POST | `/api/v1/game-versions/{versionId}/documents/{documentId}/embeddings` | Generate/store missing vectors for this document; synchronous and idempotent for the profile |
| POST | `/api/v1/game-versions/{versionId}/rules/search` | Retrieve ranked source chunks; JSON `{"query":"...","topK":5}`; topK optional |

These endpoints retrieve evidence only. They do not produce a generated answer, confidence label,
teaching flow or session. Search returns source metadata and `cosineSimilarity` (−1 to 1), which
measures vector alignment rather than confidence or factual correctness. Retrieval applies the
configured similarity threshold, metadata filtering and duplicate suppression before final top-K.
Unknown/wrong-version sources return 404, invalid input 400, an indexing size limit 413 and
storage conflicts 409. Disabled/unavailable/rate-limited providers return safe 503 errors,
provider timeouts 504 and invalid responses 502. Rule text and raw provider errors are not logged.

Indexing is separate from import. Restart the app to apply V5, then explicitly index each existing
document you want searchable. The provider is **disabled by default**. To opt in, add or update
these entries in your existing private `.env` without replacing its database credentials:

```dotenv
EMBEDDING_PROVIDER=openai
EMBEDDING_MODEL=text-embedding-3-small
EMBEDDING_API_KEY=your-private-api-key
EMBEDDING_DIMENSIONS=1536
```

Restart after changing configuration. Blank legacy provider/model/dimensions entries use the
disabled/default settings, so an older `.env` keeps startup working. Enabling OpenAI requires a
nonblank API key. Indexing and nonempty-index search send rule/query text to the configured
provider and may incur charges; no external provider calls were made during implementation checks.

The adapter follows the [official OpenAI embeddings API](https://developers.openai.com/api/reference/resources/embeddings/methods/create),
using float output and explicit dimensions. Select a model supporting the dimensions parameter.
The character limit is a resource bound, not an exact token count; provider token limits still
apply, and oversized chunks may need smaller ingestion settings and a fresh import.

For the demo document created in the previous section, after opting in and restarting:

```powershell
# Reuse $version.id and $document.id from the import demo, or replace with saved UUIDs.
$versionBase = "http://localhost:8080/api/v1/game-versions/$($version.id)"
$indexUrl = "$versionBase/documents/$($document.id)/embeddings"
Invoke-RestMethod $indexUrl
Invoke-RestMethod -Method Post -Uri $indexUrl
Invoke-RestMethod $indexUrl
$search = @{ query = 'What happens when my token reaches the last space?'; topK = 3 } | ConvertTo-Json
(Invoke-RestMethod -Method Post -Uri "$versionBase/rules/search" `
    -ContentType 'application/json' -Body $search).matches | Format-List
```

Expected: indexed counts equal total chunks, and search returns only this selected version's
eligible sources above the similarity threshold, with stable cosine tie ordering by default. An empty index returns an
empty match list without a query embedding call. Repeated indexing reuses existing profile vectors.
Changing provider/model/dimensions selects a different vector space; old vectors remain stored,
but each document must be indexed for the new profile before it contributes search results.
Concurrent indexing may duplicate provider requests, though stored vectors remain unique.
Indexing is atomic per document and defaults to at most 1000 chunks; large/background jobs and
model-vector deletion remain deferred. Prefer indexing permitted sources you are willing to send
to the selected provider.

Storage uses pgvector with a version/profile B-tree filter index and exact search, avoiding ANN
recall loss at current scale. See [pgvector's exact search documentation](https://github.com/pgvector/pgvector/tree/v0.8.2#querying).
Tests use fixed known vectors and local fake HTTP responses, independent of LLMs and API keys.

## Retrieval filters, ranking and evaluation

Version-scoped search and stateless game-question requests accept optional `topK`,
`similarityThreshold` and `filters`. Session message requests accept only `question` and use
configured retrieval defaults; these per-request filter overrides do not apply to them.
Existing bodies containing only `query` or `question` still work, with the new safe retrieval
defaults. Example for `/api/v1/game-versions/{versionId}/rules/search`:

```json
{
  "query": "What happens when my token reaches the last space?",
  "topK": 3,
  "similarityThreshold": 0.5,
  "filters": {
    "language": "en",
    "sourceTypes": ["RULEBOOK", "FAQ", "ERRATA"],
    "documentIds": [],
    "expansionDocumentIds": []
  }
}
```

For questions, replace `query` with `question`; the game still resolves its default version.
Omitted language uses the selected version's language. Language tags are normalized; filtering
does not translate text or switch editions. Source types are RULEBOOK/FAQ/ERRATA/EXPANSION.
Each ID/type set is limited to 50 entries; empty sets mean no additional restriction, except
an empty `expansionDocumentIds` always excludes EXPANSION documents. All filters intersect.

`documentIds` must belong to the selected version; unknown/foreign IDs return 404 before a
provider call. To include expansion rules, explicitly provide owned EXPANSION document IDs in
`expansionDocumentIds`; selecting a base document there returns 400. An EXPANSION source-type
filter alone does not enable expansions. This is an expansion-ready document selection boundary,
not a session, expansion catalog or automatic expansion activation feature.

Metadata constraints run in parameterized SQL before the bounded cosine candidate selection.
If no eligible indexed document exists, no query-provider call occurs. The active vector profile
and version remain mandatory regardless of filters. The candidate pool is thresholded, then
deduplicated, passed through `RuleReranker` and finally limited to top-K. The default reranker
preserves cosine order; no cross-encoder or paid reranking model is installed. A replacement
must return a permutation of the original candidates without altered scores/provenance;
invalid output or failure returns safe 502 `RERANKING_FAILED`.

Duplicate suppression retains the best-ranked occurrence of NFC/whitespace-normalized identical
text within the same version/language/source type/document version. It also suppresses substantial
contained passages in the same document when the retained passage contains the shorter candidate's source range and their
five-word shingle overlap reaches the configured limit. Short variants, negation/number changes,
different source types, languages and document versions remain distinct. A longer passage is never
dropped in favor of a shorter one. No imports are deleted.

Search responses add `diagnostics`: effective filters/threshold/candidate limit, candidate count,
below-threshold count, duplicates removed, returned count, elapsed milliseconds and
`candidateLimitReached`. Logs record counts/IDs/timing without question/document text.
The bounded pool may return fewer than top-K results if many candidates are weak/duplicate;
reaching its limit signals that later eligible evidence may have been omitted. This is exact
cosine selection within a bounded pool, not a guarantee of exhaustive deduplicated recall.

`src/test/resources/evaluation/catan-rule-questions.json` contains original narrow test paraphrases,
expected topics/sections, six supported questions and two unsupported questions. The integration
evaluation imports these into isolated PostgreSQL, adds duplicate imports and foreign rulesets,
and checks retrieval independently of generation. No commercial rulebook or paid calls are used.
Run with Docker Desktop available:

```powershell
.\gradlew.bat integrationTest --tests '*RetrievalEvaluationIntegrationTest' --rerun-tasks
```

The test writes `build/reports/retrieval-evaluation.json` with per-question diagnostics, Recall@3,
MRR@3 and unsupported-question abstentions. See development status for the actual recorded run.
Hand-assigned vectors make this a deterministic pipeline regression benchmark; it does not
measure OpenAI embedding relevance, hallucination rate or production confidence calibration.
The limited manual OpenAI walkthrough is recorded in DEVELOPMENT_STATUS.md; broader live quality evaluation is still pending.

For an offline comparison of retrieval and answer thresholds, run:

```powershell
.\gradlew.bat test --tests '*ThresholdEvaluationTest'
```

This writes `build/reports/threshold-evaluation.json` with 28 combinations: seven fictional
question cases against baseline 0.5/0.5, retrieval-only 0.4/0.5, answer-only 0.5/0.4 and
experimental 0.4/0.4 gates. Only the risk case's score (about 0.431) comes from live
diagnostic; source text, model answers, other scores and vectors are fixtures. This compares
real service gate behavior, not embedding relevance or answer quality. Production settings stay
unchanged. Unsupported-question abstention in this matrix is supplied by the provider double.
A separate characterization test demonstrates that even baseline gates accept an incorrect answer
when the model falsely claims sufficient evidence and quotes an irrelevant passage exactly.
Exact citations establish provenance, not semantic support. These results do not justify lowering
production thresholds; live labeled retrieval/answer evaluation and validation of the optional
relevance/answer-support checks remain outstanding.

## Rule question API

### Optional relevance and answer-support review

The default answer path retains its 0.5 retrieval/evidence gates. To test the experimental review
path locally, add this to your private `.env` and restart the application:

```dotenv
RAG_QUERY_REWRITE_ENABLED=true
RAG_EVIDENCE_REVIEW_ENABLED=true
RAG_EVIDENCE_REVIEW_CANDIDATE_FLOOR=0.35
RAG_EVIDENCE_REVIEW_CANDIDATE_COUNT=12
```

Keep the existing `RAG_SIMILARITY_THRESHOLD` and `ANSWER_MINIMUM_EVIDENCE_SIMILARITY` settings.
When enabled, answer requests use a separate pool with the review floor (0.35 by default), instead
of these two baseline gates. Ordinary search endpoints keep their baseline threshold. This is an
explicit experimental acceptance policy, not a calibrated confidence improvement.

After version/profile/metadata filtering, one embedding/search retrieves a bounded, deduplicated
pool. A model selects only supplied chunk IDs relevant to the original question and contextual
action. Selected passages enter generation; the existing exact quote, supplied-ID and version
checks still run, including at most one quote correction. A second review then checks whether
the **validated cited quotes alone** support every answer claim and whether the answer addresses
the current question. Uncited rule passages are excluded from that support check. Original cosine
scores are preserved; accepted reviewed answers are capped at MEDIUM.

Selection refusal, unknown/duplicate IDs, no relevant evidence, oversized review input or a safe
provider error returns LOW. A rejected support check also returns LOW; it never repairs or fabricates
an answer. Logs show `Evidence relevance review completed` and `Cited answer support review completed`
with outcomes/timing, without rule bodies, questions, answers or keys. Fallback reasons include
`NO_RELEVANT_EVIDENCE` and `ANSWER_SUPPORT_REJECTED`. The existing request ID links these events.

Reviews use the configured `LLM_*` model, effort, token budget and per-call timeout, sharing the
existing [Responses structured-output adapter](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses).
They add up to **two paid model calls per question** when you test with OpenAI. With query rewriting
and quote correction, one question can use up to five LLM calls plus its query embedding; speech
has separate costs. There is no whole-pipeline timeout or review retry. Review inputs cap selection
at 32000 and verification at 40000 serialized characters, in addition to the existing prompt budget.
Candidate limits/budgets can omit necessary rules. Explicit question API top-K/threshold overrides
can restrict the review pool, not expand it beyond the configured count/floor.

To retest, ask “Can I keep all four?” then “What's the risk?” during the initial ticket setup step,
expand sources and check whether the actual scoring passage is cited. Also repeat quantity-bound
questions and an unsupported question. No game-specific answer or scoring rule is hardcoded.
Mock providers cover lower-scoring source admission and unsupported-answer rejection, but
**broader live review quality is unverified**. A limited manual walkthrough answered the risk
follow-up and quantity/return/discard questions, resisted a rule-override instruction and abstained
on an unsupported phone number. One correct unfinished-ticket answer cited only a general point-loss
warning for its more precise amount/timing claim: quote-level support remained incomplete.
Generic prompts now require each amount, calculation, timing, condition and exception to follow
from actual quoted premises; otherwise generation should choose a precise quote or simplify the
answer, and review should reject unsupported detail. A subsequent manual retest cited the specific
Game End deduction rule and resolved that individual citation gap; broad reliability is unverified.
A reviewer using the same model may repeat its mistakes;
structured outputs and exact quotes do not guarantee correctness. Turn
`RAG_EVIDENCE_REVIEW_ENABLED=false` and restart to return to baseline behavior.

`POST /api/v1/games/{gameId}/questions` accepts:

```json
{
  "question": "What happens when my token reaches the last space?"
}
```

The backend resolves the game's active default GameVersion, then reuses version/profile-scoped
retrieval. Clients do not select an edition. This stateless endpoint resolves the current default;
use the session endpoints below for a pinned ruleset and conversation history. Questions must be nonblank and at most 4000
characters, also within the configured embedding input limit.

For live testing, first import and index a permitted document as described above. Add or update
these entries in your existing private `.env`, preserving database and embedding settings:

```dotenv
LLM_PROVIDER=openai
LLM_MODEL=gpt-4.1-mini
LLM_API_KEY=your-private-api-key
```

To optionally use [GPT-5.6 Luna](https://developers.openai.com/api/docs/models/gpt-5.6-luna),
keep the same provider/key settings and change these entries:

```dotenv
LLM_MODEL=gpt-5.6-luna
LLM_REASONING_EFFORT=none
LLM_MAX_OUTPUT_TOKENS=800
```

The application explicitly defaults Luna to `none`, rather than the model's provider default
of `medium`. To experiment with reasoning, select an effort such as `low` and consider increasing
`LLM_MAX_OUTPUT_TOKENS` to `4096` within the application's 128–4096 limit. The
[output budget includes reasoning and visible answer tokens](https://developers.openai.com/api/docs/guides/reasoning#controlling-costs).
Higher effort can exhaust this bounded budget or exceed the configured timeout; incomplete output
returns `LLM_INVALID_RESPONSE` (502), without accepting partial answers or automatically retrying.
No effort level guarantees better grounding or lower latency. Live Luna quality, cost and latency
have not been measured. Selecting a model does not itself call OpenAI.

Luna requests send `reasoning.effort` and omit `temperature`, `top_p` and `logprobs` at every
effort. GPT-4.1 Mini continues to send `temperature=0` without a reasoning parameter. To switch
back, set `LLM_MODEL=gpt-4.1-mini` and `LLM_REASONING_EFFORT=none`. Unsupported effort values
or non-`none` effort with another model fail configuration validation. Only the exact Luna alias
above selects the Luna adapter behavior; other models retain the previous request behavior and
are not newly validated for compatibility. Restart the application after changing settings.
Changing this answer model requires no document reimport, embedding reindex or database migration;
embedding and speech settings remain independent. Keep the actual key in your private environment.

Both embedding and LLM providers are opt-in. Blank legacy LLM settings remain disabled/default.
Restart after configuration changes. No extra database migration is needed for Milestone 4;
V6 adds sessions/teaching and V7 adds guided setup. Requests with indexed rules may incur a query-embedding charge and an
LLM charge. Default tests require neither keys nor paid calls. A private key may be used in both
key settings if it has appropriate permissions, but each setting must be configured explicitly.

Using the demo game's ID from the import walkthrough:

```powershell
$question = @{ question = 'What happens when my token reaches the last space?' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/v1/games/$($game.id)/questions" `
    -ContentType 'application/json' -Body $question | ConvertTo-Json -Depth 8
```

You can also execute this endpoint in Swagger. The response contains `gameId`, `gameVersionId`,
`answer`, `confidence` (`HIGH`, `MEDIUM`, `LOW`) and `sources`. Each accepted source contains
stored chunk/document/version IDs, document/type/version/language/reference, section/page,
chunk offsets, an exact supporting quote and cosine similarity. Source metadata is resolved
from storage, never trusted from model-generated names or pages.

The dedicated prompt builder keeps trusted instructions separate from JSON-encoded untrusted
questions/document data, prohibits invented rules and asks the model to decline unsupported or
conflicting evidence. Context preserves complete chunks in retrieval order within its serialized
character budget. Generation uses the [OpenAI Responses structured-output contract](https://developers.openai.com/api/docs/guides/structured-outputs),
temperature 0 for GPT-4.1 Mini or explicit reasoning effort without sampling parameters for Luna,
a bounded output budget and `store=false`; no tools or provider-side conversation persistence.
Luna reasoning items are validated and discarded; only completed assistant output text supplies
the structured answer. Refusals return insufficient evidence; malformed/incomplete responses and
unexpected tool items are rejected. Citation and confidence gates apply identically to both models.
Session conversation history is stored locally by the application.
The default [GPT-4.1 mini model](https://developers.openai.com/api/docs/models/gpt-4.1-mini) supports this contract.

The current question is placed after conversation history in the model input. Prompt instructions
prioritize its explicit quantities, negations and conditions and require a direct answer for the
current situation. These are prompt safeguards, not semantic validation: a cited answer may still
misinterpret a question. A user-reported one-after-two follow-up failed live testing; the prompt
adjustment has mock-provider coverage, and its effect on real-model answers needs a live repeat.
The prompt also allows direct conclusions from explicit quantities and conditions, including
limits when choosing items from a fixed initial deal. It requires citations to the actual rule
wording and forbids supplying missing premises or inventing exceptions. A subsequent user log
showed `MODEL_INSUFFICIENT_EVIDENCE` despite including the relevant setup passage; this motivates
the deduction instruction but does not establish that it fixes live generation.

Confidence is a baseline evidence heuristic, not a calibrated probability. With no supplied chunk
meeting the minimum gate, generation is skipped. The model must also report sufficient evidence;
every returned citation must identify a supplied chunk, quote its content (allowing whitespace-run differences only) and meet the
minimum gate. Unknown/excluded citations, invented quotes or duplicate citations fail closed.
Accepted citations return the original contiguous source span, including original paragraph breaks.
Only whitespace runs may differ: words, numbers, punctuation, case and order must match. Ellipses
cannot bridge omitted text, and matching never searches another chunk on behalf of the cited ID.
Both generated and restored quotes are bounded at 1000 UTF-16 characters. Whitespace-only differences
do not trigger a correction call; other mismatches retain the bounded correction and LOW fallback.
The minimum quote-length gate for HIGH confidence uses the restored source quote, so model-added
spaces cannot inflate that check.
Accepted answers are HIGH only if every cited chunk meets the HIGH gate, all strong prompt
evidence is cited, and every quote meets `ANSWER_MINIMUM_QUOTE_CHARACTERS`; otherwise MEDIUM.
This conservatively downgrades partial evidence coverage and trivial quotes.
Empty/weak evidence, refusal or failed citation checks return HTTP 200, LOW, no sources and:

> I couldn't verify that confidently from the available rules.

Retrieval now filters weak matches and duplicates before the answer prompt is built. Its threshold
and the separate answer acceptance gates both apply, so lowering the retrieval threshold alone
does not allow weak cited evidence into an answer. Real-model relevance, threshold calibration
and resistance to adversarial prompts remain unverified. Exact
quotes validate provenance but cannot prove that all generated prose is semantically supported.
A narrow live OpenAI walkthrough has passed; broader retrieval/grounding evaluation remains deferred.

Unknown games return 404; inactive games/default versions or missing defaults return 409;
invalid questions return 400. Existing embedding errors retain their codes/statuses. Disabled,
unavailable or rate-limited LLMs return 503, timeouts 504, and malformed/incomplete output 502.
Provider outages remain errors rather than being disguised as low-confidence answers. With
embeddings configured and an empty index, no query-embedding or LLM call is made; if embeddings
are disabled, the existing `EMBEDDING_NOT_CONFIGURED` error still applies.

## Sessions and teaching API

Restart the application to apply V6 and V7. Existing content and sessions are preserved.
Since V9 the reference teaching flow is Ticket to Ride: twelve concise sections, of which QUICK selects nine,
STANDARD eleven and DETAILED twelve, including PHYSICAL_SETUP at every depth. It is an introductory
overview, not a complete commercial rulebook. Seven authored setup steps record the user's confirmation;
the application cannot inspect the physical board.
Teaching/navigation require no provider keys, indexing or paid calls.

Create a session in Swagger or PowerShell:

```powershell
$base = 'http://localhost:8080/api/v1'
$body = @{ gameId = '00000000-0000-0000-0000-000000000001'; playerCount = 4 } | ConvertTo-Json
$lesson = Invoke-RestMethod -Method Post -Uri "$base/sessions" -ContentType 'application/json' -Body $body
$sessionId = $lesson.session.id
$lesson | ConvertTo-Json -Depth 10
```

Creation returns HTTP 201 and a Location header. Optional `teachingDepth` is QUICK, STANDARD
(default), or DETAILED. The backend validates player count, resolves the active default ruleset
once and pins its GameVersion, current TeachingFlow and configured SetupFlow. Changing either the catalog default
or publishing a replacement flow does not change existing sessions. A version needs a configured
flow before a session can be created; missing configuration returns 409.

`GET /sessions/{id}` returns persisted session state, current lesson, displayed explanation,
lesson number/total, completed section keys, revision and readiness notice. Navigation accepts
the most recently returned revision to prevent accidental double advancement:

```powershell
$body = @{ action = 'CONTINUE'; expectedRevision = $lesson.session.revision } | ConvertTo-Json
$lesson = Invoke-RestMethod -Method Post -Uri "$base/sessions/$sessionId/teaching/actions" `
    -ContentType 'application/json' -Body $body
$lesson | ConvertTo-Json -Depth 10
```

Actions are CONTINUE, PREVIOUS, REPEAT and EXPLAIN_AGAIN. CONTINUE normally completes the current
section and advances. At a section with setupTrigger=true, it starts unfinished setup instead,
saving this lesson as the return position without completing it. PREVIOUS preserves completed progress, stopping at
the first lesson. REPEAT returns the saved explanation. EXPLAIN_AGAIN presents the same authored
content with its examples/tips, without an LLM rewrite or progress change. Every action updates
the revision; stale requests return `SESSION_CONFLICT` (409). Reload with GET before retrying.
Once required triggered setup is confirmed, the final teaching CONTINUE enters PLAYING in the
same session and displays a ready-to-play notice. A final lesson that triggers unfinished setup
enters SETUP first; completing setup returns to TEACHING before that final Continue.
Lesson navigation is restricted to TEACHING. Setup completion returns to the saved lesson in
TEACHING; a further CONTINUE advances normally. A completed setup trigger does not run twice.

Questions work during teaching, setup and playing through the existing grounded-answer pipeline:

```powershell
$body = @{ question = 'How do I collect resources?' } | ConvertTo-Json
$reply = Invoke-RestMethod -Method Post -Uri "$base/sessions/$sessionId/messages" `
    -ContentType 'application/json' -Body $body
$reply | ConvertTo-Json -Depth 10
$lesson = Invoke-RestMethod -Uri "$base/sessions/$sessionId"
```

Use `question`, not `message`. Questions preserve the current lesson, completed sections,
setup step, confirmed setup steps and saved teaching return point.
The response contains `sessionId`, new `revision`, `currentSectionKey` and the existing nested
`answer`/confidence/sources contract. Successful answers and LOW fallbacks persist an atomic
USER/ASSISTANT pair, including source citations. Provider failures persist neither message and
leave workflow state unchanged. Live Q&A still requires explicitly configured embedding/LLM
providers and indexed permitted documents for the pinned version. The seeded Ticket to Ride lessons are
not automatically imported or indexed as rule evidence. With providers disabled, Q&A returns
the existing safe 503 errors; teaching continues to work.

`SessionContextService` loads game, pinned edition/language, player count, depth, stage, current
lesson, active setup step during SETUP and bounded recent messages. Defaults are `SESSION_RECENT_MESSAGE_LIMIT=8` (2–50) and
`SESSION_CONTEXT_CHARACTERS=8000` (1000–16000). They are optional additions to a private `.env`;
omitting them uses defaults. Only whole recent messages within both limits are retained.
The retrieval query preserves the current question, then adds the latest retained user question,
active setup title (during SETUP), lesson title, setup instruction and lesson explanation within the available budget.
Titles are capped at 160 characters each and the prior question at 400. For questions of at most
80 Unicode code points with a nonblank prior user question, instruction/explanation cues are capped
at 120 characters each instead of 800, so verbose authored guidance does not dominate short follow-ups.
Initial questions without a prior user question and longer questions retain the 800-character cues.
This is a length heuristic, not semantic reference resolution; short unrelated questions may still
inherit an irrelevant prior topic. Earlier assistant claims are excluded from retrieval cues.
Truncation preserves Unicode boundaries. These topic cues help natural follow-ups such as "Can I just keep two?".
The complete query is capped at
4000 characters and the configured embedding input limit. The LLM receives structured context plus newly retrieved evidence.
Optional contextual query rewriting can replace this deterministic query for short session follow-ups:

```dotenv
RAG_QUERY_REWRITE_ENABLED=true
```

Keep the existing `LLM_*` settings and restart Spring Boot. No reimport or reindex is needed.
With an indexed version, a question of at most 80 Unicode code points and retained prior USER
history, this adds **up to one LLM call** before the normal query embedding and answer generation.
It uses the same model, reasoning effort, timeout and output budget as answering. This may increase
cost and latency; timeouts apply per call, not to the entire question pipeline. Quote correction can
still add its separate answer call. Stateless questions, long questions, initial questions without
user history and empty indexes skip rewriting. A short already-standalone question may still incur
the call before the rewriter declines to change it.

The rewriter receives the latest two retained user questions (400 characters each), game/edition,
stage and authored lesson/setup titles. Earlier assistant claims and authored instructions are
excluded. It is instructed to clarify references, explicitly expand risk/downside into consequences
or penalties, and omit unnecessary game-name/edition framing because search already filters by version.
Consequences may occur later than the current setup/lesson stage. The query must not invent a penalty,
amount, condition or outcome. Rewritten text is a search cue only: the answer
generator still receives the **original question** and must cite retrieved source passages.
The rewrite contract is separate from the answer/citation contract. Refusal, unresolved references,
invalid/oversized output or a safe provider error retain the deterministic query without rewrite retries.
No confidence/citation threshold or game/version filter is relaxed. Model-generated queries can still
misinterpret the user's intent; automated doubles do not establish real retrieval quality.

Look for `Contextual query rewrite completed` with `outcome=APPLIED`, `UNRESOLVED`, `INVALID`
or a safe LLM error code, followed by the usual retrieval/evidence/fallback events with the same
`requestId`. These logs omit question, rewrite, history and source text. Set the flag to `false`
and restart to disable the additional call. Earlier manual live rewrites still failed the risk
follow-up; the refined consequence-search instructions await live retesting. No paid calls were
made during implementation.

For a focused local investigation of an applied rewrite that still returns LOW, temporarily set
`RAG_QUERY_DIAGNOSTICS_LEVEL=DEBUG` and restart. `Rule retrieval query detail` logs the effective
query (at most 1000 UTF-16 characters, with original length/truncation flag) and up to 500 candidate
chunk/document IDs, bounded section titles, cosine scores and `passesThreshold`, including rejected
candidates. It does not print rule bodies, API keys or raw provider responses. **It does print search
text that may contain private user details**, so keep it local, inspect before sharing and switch
back to `OFF` afterward. JSON escaping preserves whitespace safely. The flag adds no API calls;
manually asking a question still uses the configured paid pipeline. This is diagnostic only and
does not fix retrieval, change ranking or lower thresholds. The first live rewrite retest applied
successfully but still missed Game End; see DEVELOPMENT_STATUS.md for the recorded limitation.
Older answers, lesson text and the current setup instruction are untrusted context, never substitutes for source evidence.
The existing serialized prompt budget also applies and can further remove older context to preserve evidence.
The limited live walkthrough demonstrated one follow-up; broader live-model understanding remains
unverified. Setup-specific Q&A has automated mock-provider coverage only. Session expansion selection and summarization are not implemented.

To diagnose a LOW answer, find console events with the same `requestId`: `Rule retrieval completed`,
`Rule answer evidence`, `Rule answer fallback` and `Rule question completed`. Evidence events identify
chunk/document IDs, section/page, cosine similarity and `includedInPrompt`. Fallback `reason` distinguishes
`NO_RETRIEVED_EVIDENCE`, `PROMPT_BUDGET_EXCLUDED_EVIDENCE`, `NO_STRONG_EVIDENCE`,
`MODEL_INSUFFICIENT_EVIDENCE`, `UNKNOWN_CITATION`, `DUPLICATE_CITATION`, `QUOTE_MISMATCH`,
`WEAK_CITATION` and `NO_CITATIONS`. These diagnostics omit question/answer text, rule bodies and quotes.
They explain rejection stages; they do not prove that the selected passages are relevant or the model is accurate.

An otherwise valid response with a mismatched quotation gets at most one correction attempt using the
same question, context and retrieved excerpts. Instructions require exact contiguous quotes paired with
their own chunk IDs. The rejected answer/quote is not reused as evidence. The complete replacement
response must pass every citation and evidence check; another mismatch returns LOW. Unknown, duplicate
or weak citations and insufficient evidence do not trigger correction. This can add one LLM call and
its latency/cost (each call uses the configured timeout); it does not repeat retrieval or indexing.
Look for `Rule answer correction requested` / `Rule answer correction completed` and `attempt=2`.
Provider errors remain errors, and only the final validated answer or LOW fallback is stored in history.

For a controlled local quote comparison, temporarily add `ANSWER_QUOTE_DIAGNOSTICS_LEVEL=DEBUG`
to your private `.env`, restart the application and reproduce the question once. The dedicated logger
`com.boardgamemaster.rag.quotediagnostics` emits `Rule quote mismatch detail` with `attempt`,
the rejected `quote`, its `citedContent`, and `exactQuoteFoundInOtherChunkIds` (other prompt chunks
containing that exact quote). Text is JSON-escaped so whitespace differences remain visible; source
content is capped at 8000 characters with `contentTruncated` and the original length recorded.
This opt-in output contains imported rule text and model-generated quotes: keep it local and out of Git.
Set `ANSWER_QUOTE_DIAGNOSTICS_LEVEL=OFF` and restart after capture. OFF is the default, independent
of ordinary pipeline logging. This adds no provider calls and does not relax citation checks.

Read history (oldest first within each bounded page):

```powershell
Invoke-RestMethod -Uri "$base/sessions/$sessionId/messages?limit=20" | ConvertTo-Json -Depth 12
```

Use the first returned `sequenceNumber` as `beforeSequence` for older pages. Limits are 1–100.
All stored history remains in PostgreSQL; only recent bounded context is sent to the provider.
Concurrent questions/navigation are guarded by session revisions. An answer generated against
changed session state is discarded with 409 and no partial exchange; an already completed
provider request may still incur a charge. No transaction or row lock spans provider calls.

End the session explicitly from any active stage:

```powershell
$lesson = Invoke-RestMethod -Uri "$base/sessions/$sessionId"
$body = @{ expectedRevision = $lesson.session.revision } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/sessions/$sessionId/complete" `
    -ContentType 'application/json' -Body $body | ConvertTo-Json -Depth 10
```

Completion sets stage/status to COMPLETED and records `endedAt`. Reads/history remain available;
further questions and navigation return 409. Unknown sessions return 404; malformed requests and
invalid history bounds return 400. Player-facing session APIs are open by design; only admin
pages and content-changing catalog/rule APIs are password-protected.

For another game/version, publish a flow with
`POST /api/v1/game-versions/{versionId}/teaching-flows`:

```json
{
  "name": "Original demo lessons",
  "sections": [
    {
      "key": "TAKING_A_TURN", "order": 0, "title": "Taking a turn",
      "explanation": "Move your token forward one space. If the destination contains another token, move to the next empty space instead.",
      "depths": ["QUICK", "STANDARD", "DETAILED"],
      "examples": [],
      "tips": [], "references": ["Lantern Trail: Taking a turn"]
    }
  ]
}
```

GET the same URL to inspect the latest flow. Publishing is append-only: the most recently
published flow is used for future sessions. Each flow has 1–100 uniquely keyed/ordered sections
and at least one applicable section for each depth. Omitted depths mean all three; omitted
examples/tips/references are empty. Explanations are authored, bounded and game-specific data.
References are author-supplied reading pointers, not generated verified citations. Shared teaching
logic has no Catan-specific sequence. Only publish content you have permission to use.

Automated checks include session/domain/context tests and isolated PostgreSQL/HTTP integration
tests with known-vector embedding and LLM doubles. The limited live walkthrough supplements
these results; broader evaluation remains deferred. See the browser and voice demonstrations below.

## Guided setup API

Restart the application to apply V7; retain the existing database volume. No new environment
variables or provider keys are needed for setup. In Swagger, create a Ticket to Ride session, Continue
through INTRODUCTION and OBJECTIVE to PHYSICAL_SETUP, then Continue again to enter
SETUP. `GET /api/v1/sessions/{id}/setup` now displays BOARD, step 1 of 7, including notes/warnings.
The reference text is original, concise beginner guidance checked against the Ticket to Ride
rules booklet (Days of Wonder, 2025). Commercial rulebook files are not bundled.
The text records confirmation rather than detecting board state, and is not an indexed Q&A source.

Every POST below accepts `{ "expectedRevision": <latest session revision> }`:

| Endpoint under `/api/v1/sessions/{id}/setup` | Behavior |
| --- | --- |
| GET (no suffix) | Current step, number/total, pinned flow and persisted progress |
| POST `/start` | Start explicitly from a TEACHING lesson and save its return position |
| POST `/next` | Confirm this step and show the next; at the last step use complete |
| POST `/previous` | Move back, preserving confirmed steps; at the first step stay there |
| POST `/repeat` | Show this step again without changing progress |
| POST `/restart` | Reset setup to its first step; retain teaching progress and conversation |
| POST `/complete` | Confirm the final step after all previous steps; return to saved TEACHING lesson |

Example after entering setup (using `$base` and `$sessionId` from above):

```powershell
$setup = Invoke-RestMethod -Uri "$base/sessions/$sessionId/setup"
$setup | ConvertTo-Json -Depth 10
$body = @{ expectedRevision = $setup.session.revision } | ConvertTo-Json
$setup = Invoke-RestMethod -Method Post -Uri "$base/sessions/$sessionId/setup/next" `
    -ContentType 'application/json' -Body $body
```

Read each instruction and repeat Next with the latest revision until the final step. Then POST
`/setup/complete`. GET the session: stage must be TEACHING at PHYSICAL_SETUP, not PLAYING.
Continue teaching until the same session enters PLAYING. No provider calls occur in this journey.
Stale revisions, premature completion, Next at the final step and setup actions during PLAYING or
COMPLETED return 409. Restart is available after setup has started, during SETUP or resumed TEACHING;
it clears setup confirmations and saves the current teaching return point, without deleting history.

To test interruption, ask through the existing `/sessions/{id}/messages` endpoint during SETUP
using an indexed permitted document and configured providers. Reload setup afterward: only the
revision should have increased; the step, confirmations and return lesson must remain unchanged.
These questions may incur provider charges. A provider failure stores no partial exchange; an
in-flight answer loses a revision race with setup navigation/restart and returns 409 without history.

For another game, publish setup content before a teaching flow containing a trigger. Using the
Lantern Trail `$version.id` and `$game.id` from the earlier demo (never the Ticket to Ride reference IDs):

```powershell
$flow = @{
    name = 'Lantern Trail guided setup'
    steps = @(
        @{ key = 'TRAIL'; order = 0; title = 'Prepare the trail'; instruction = 'Place five paper spaces in a row. Give each player one token and put all tokens on the first space.' }
        @{ key = 'MARKERS'; order = 1; title = 'Prepare markers'; instruction = 'Put three spare markers beside the trail. Choose a starting player together.'; notes = @('Confirm the table together.'); warnings = @() }
    )
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$base/game-versions/$($version.id)/setup-flows" `
    -ContentType 'application/json' -Body $flow
$flow = @{
    name = 'Lantern Trail lessons with setup'
    sections = @(
        @{ key = 'PREPARE'; order = 0; title = 'Prepare to play'; explanation = 'Continue to prepare the physical table.'; setupTrigger = $true }
        @{ key = 'TURN'; order = 1; title = 'Taking a turn'; explanation = 'Move your token forward one space. If the destination contains another token, move to the next empty space instead.' }
    )
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$base/game-versions/$($version.id)/teaching-flows" `
    -ContentType 'application/json' -Body $flow
```

Create a **new** session for this demo game, then Continue at PREPARE to enter setup. The same
engine executes these two steps and returns to PREPARE. A second Continue moves to TURN; the
next finishes teaching. Existing sessions keep their earlier flows and progress. This demonstrates
generic setup without changing shared code or requiring paid Q&A.

GET the setup-flows publication URL to inspect its latest configuration. If the session has no
pinned flow and its version has no configured setup flow, GET `/sessions/{id}/setup` returns
409 `SETUP_FLOW_NOT_FOUND`. Publications are immutable,
version-bound, and contain 1–100 steps with unique uppercase keys and nonnegative unique orders;
title/instruction are required,
notes/warnings optional. Teaching flows allow at most one setup trigger, applicable to all three
depths so QUICK cannot skip required setup. Missing setup configuration for a trigger returns 409.
New sessions pin the current setup flow when configured. Legacy sessions without a pin may choose
that version's latest flow on their first explicit start; afterward the pin is fixed. Later publications
or catalog default changes never alter a pinned setup. Legacy teaching flows without triggers retain
their existing behavior and can enter PLAYING without a recorded setup confirmation.


## Voice

### Browser questions and optional voice

For cited answers, use the separate Lantern Trail game/version created in the
[rule-document API](#rule-document-api) demo above. Import its original sample, explicitly index
it and publish teaching/setup flows with the APIs, or do the same in the admin area. The complete
customer journey then runs in the browser without Swagger or Postman.
Select that game on the start screen. Existing sessions keep their original version/flow pins,
so create a new session after publishing a setup-trigger teaching flow.

Ask **What happens when my token reaches the last space?** during teaching, setup or playing.
The answer appears in **Your conversation**, with confidence and expandable **View sources**
containing exact quotes and source metadata. Ask a follow-up, such as **Where does my token go
after that?**, and confirm that the lesson/setup position stays unchanged. Unsupported questions
may return the fixed LOW-confidence fallback. This is a manual demonstration, not a grounding benchmark.

Speech is independently opt-in. These defaults now have active meanings in `.env.example`:

| Setting | Default / purpose |
| --- | --- |
| `STT_PROVIDER` | `disabled`; set `openai` only when ready to send audio |
| `STT_MODEL` | `whisper-1` |
| `STT_API_KEY` | Blank; supply privately for enabled transcription |
| `STT_TIMEOUT` | `30s`; positive, at most `120s` |
| `TTS_PROVIDER` | `disabled`; set `openai` only when ready for synthesis |
| `TTS_MODEL` | `tts-1` |
| `TTS_API_KEY` | Blank; supply privately for enabled synthesis |
| `TTS_TIMEOUT` | `30s`; positive, at most `120s` |
| `TTS_VOICE` | `alloy`; also `echo`, `fable`, `onyx`, `nova`, `shimmer` |
| `VOICE_MAX_AUDIO_BYTES` | `2097152` (2 MiB); configurable from 1 KiB to 8 MiB |

Speech keys are separate settings and do not automatically inherit `LLM_API_KEY`. STT and TTS
can be enabled independently. For paid end-to-end voice Q&A, embedding and LLM configuration
must also be ready. Keep all real keys in your ignored `.env` or process environment, then restart
the application. Nothing automatically indexes documents or enables paid calls, except an
explicit PDF import in the admin area, which indexes the rulebook it imports.
The adapters follow OpenAI's [transcription contract](https://developers.openai.com/api/reference/resources/audio/subresources/transcriptions/methods/create)
and [speech contract](https://developers.openai.com/api/reference/resources/audio/subresources/speech/methods/create).

With STT enabled, **Hold to speak** requests microphone access only while you initiate a recording.
Hold the pointer button or Space/Enter, ask one short question, then release to send. Recording
stops at 60 seconds or the byte limit; leaving/focusing away from the page cancels recording.
Use localhost or HTTPS and allow microphone permission. If the browser cannot record, upload a
short WAV, WebM, Ogg, MP3 or MP4 recording, or use text. The server checks declared MIME type,
container signature and size; it does not fully decode uploaded codecs or enforce audio duration.
The servlet's separate 10 MB multipart request limit also applies.

The page displays transcription, saved answer and citations. Generated MP3 audio has playback
controls; if autoplay is blocked, press Play. Spoken answers are AI-generated, as disclosed in
the UI and [OpenAI's speech guidance](https://developers.openai.com/api/docs/guides/text-to-speech).
STT failure leaves progress/history unchanged. If answering fails after transcription, recognized
text remains visible for correction or typed retry. If TTS fails or is disabled, the saved text and
citations remain usable. No automatic provider retries occur. Refresh before retrying a network
failure because a question may already have been saved. Concurrent navigation can discard a
stale voice answer; provider requests already completed may still incur costs.

### Spoken teaching and setup guidance

TTS also reads authored lessons and setup instructions; STT, embeddings and LLM providers are
not needed for this narration. Enable `TTS_PROVIDER=openai` and privately configure `TTS_API_KEY`,
then restart the application. On the start screen, select **Speak explanations** before **Start game**
to hear the first lesson. In an existing teaching/setup session, enable **Speak explanations** there.
The choice is remembered within the browser tab. New lessons and setup steps are narrated as
you navigate; **Repeat** and **Explain again** also request the displayed explanation.

Use the audio controls to pause/resume, **Stop speaking** to stop, and **Listen / Replay** to hear
the current guidance again. Replay reuses audio already loaded on the current page. If autoplay
is blocked, press Play. Refreshing, asking a question or returning from review does not automatically
narrate the same content again when browser session storage is available; explicit Listen remains
available. Speaking a question stops narration so the microphone does not pick up the explanation.

Narration uses the configured AI voice and incurs TTS usage. It reads the authored heading and
lesson explanation, or the current setup instruction with its notes/warnings. **Explain again**
uses the same expanded explanation shown as text. It does not invent rules, retrieve sources,
advance progress or add conversation messages. Long guidance is split into segments of at most
4096 characters; the next segment is generated after playback finishes the current one. Pausing
or stopping prevents generation of later segments, but an already submitted request may still
incur usage. Text and navigation remain available if narration fails. Browser decoding/playback
was checked with synthetic silent MP3; live narration quality and intelligible speech remain unverified.

`POST /api/v1/sessions/{sessionId}/voice/narration` accepts JSON with `expectedRevision`,
optional `explainAgain` (default false) and optional `segmentIndex` (default 0). The server selects
the pinned current lesson/setup content; clients cannot submit arbitrary narration text.
It returns the session/revision/stage/section, segment index/count, segment text and MP3 metadata.
Narration is available only in active TEACHING/SETUP sessions. Stale revisions or progress changes
during synthesis return 409; invalid segments return 400. Provider failures use the normal safe
speech errors. Synthesis occurs outside database transactions, and audio is not persisted.

### Voice API

`POST /api/v1/sessions/{sessionId}/voice` accepts `multipart/form-data` with a required binary
part named `audio`. Success returns `transcription`, `message` (the existing session answer,
revision and citations), `audio` (`mediaType: audio/mpeg`, `base64`) and nullable `answerError` /
`audioError`. Audio exists only for the response and browser playback; the database stores the
usual text conversation and source metadata, without raw audio or generated audio files.

Invalid/empty audio returns 400, oversized audio 413, invalid provider responses 502,
unavailable/disabled/rate-limited providers 503 and timeouts 504 using safe error messages.
Missing/completed sessions return 404/409 before speech calls. After successful transcription,
an answer failure returns its HTTP status with transcription and `answerError`; no partial
conversation is stored. TTS failure returns 200 with the saved `message`, `audio: null` and
`audioError`. Synthesis accepts up to 4096 characters; a longer answer keeps its full text and
returns a structured audio error. Speech HTTP bodies are bounded (64 KiB STT, 4 MiB TTS).

Automated validation uses local HTTP fixtures, mock providers and disposable databases.
Synthetic-microphone browser checks verify recording, transcription, audio controls, desktop/mobile
layout and the full journey; they do not establish real transcription accuracy or intelligible
speech playback. Live speech testing and broader live RAG evaluation remain deferred.


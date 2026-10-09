# Architecture

Board Game Master is a modular monolith. The root package is `com.boardgamemaster`, with
packages grouped by feature rather than by technical layer. Each feature adds domain,
application, infrastructure and API code where it needs them.

| Package | Responsibility |
|---|---|
| `game` | Catalog metadata, game versions and default resolution |
| `rules` | Document import, normalized source text and chunks |
| `rag` | Indexing, retrieval, prompts, answers and evidence checks |
| `session` | Persisted progress, teaching content and conversation history |
| `setup` | Version-bound setup flows and teaching interruption/resume |
| `ai` | Provider contracts and embedding, LLM and speech adapters |
| `voice` | Session voice and narration orchestration |
| `ui` | Thymeleaf pages and browser interaction |
| `common` | Shared configuration and errors |

`gamemaster` is reserved for a future orchestration boundary. It does not add another service
layer yet. Text, voice, Swagger and browser pages reuse the existing application services.

## Catalog and version ownership

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

## Rule documents and chunking

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

## Embedding storage and indexing

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

## Retrieval and cited answers

`RuleRetrievalService` validates the version and input, embeds the query, and searches only the
active provider/model/dimension profile within that version. Empty indexes return no matches
without a provider request. Exact cosine ordering uses deterministic document/chunk tie-breaks.
The filtered candidate CTE is materialized so vectors of other dimensions are never evaluated.
A B-tree index supports version/profile filtering; no ANN index is used at MVP scale, preserving
exact recall and allowing configurable dimensions (1–2000). HNSW/IVFFlat requires later scale and
recall evidence. Milestone 5 applies language/type/document/expansion selection before the
candidate limit, then similarity thresholding, duplicate suppression, a reranking boundary
and final top-K (1–50). Metadata SQL remains parameterized and the materialized profile
candidate CTE continues to isolate dimensions safely. No schema migration is needed.

REST operations expose explicit document indexing/status and version-scoped source matches,
including document/version IDs, source type/version/language/reference, section/page, offsets
and cosine similarity. Logs contain timing/IDs/counts, never rule/query text or API keys.
`RuleAnswerService` resolves the selected game's active default through `GameService`, reuses
`RuleRetrievalService`, builds a bounded prompt and generates through `LlmClient`. The question
endpoint accepts a game ID and question; it creates no session and persists no conversation.
Each request resolves the current default once and uses that version for all evidence and
returned sources. No database transaction spans external calls. No new schema is needed.

`RuleAnswerPromptBuilder` supplies static trusted instructions separately from JSON-encoded
untrusted question/excerpts/metadata. It instructs the model to use only the selected version's
evidence, ignore embedded instructions, decline insufficient/conflicting evidence and cite
supplied chunk IDs with exact supporting quotes. Whole chunks remain in retrieval order within
the serialized context-character budget; an oversized next chunk ends context selection.

The typed LLM result contains answer, evidence-sufficiency flag and citations. Application
checks require every citation to identify a chunk actually supplied in the prompt, contain an
nonblank source quote matching exactly except for whitespace-run differences, be unique and meet the minimum answer evidence gate. Stored
metadata supplies public source names, IDs, versions, section/page and chunk offsets. Invented
or excluded sources/quotes fail closed. Provider refusal or insufficient evidence discards
generated text. No usable evidence skips generation. All these paths return a fixed fallback,
LOW confidence and no sources; provider errors retain meaningful HTTP errors instead.

`boardgame.answer.*` defines baseline acceptance gates: minimum similarity 0.5 and HIGH 0.8.
Accepted answers are HIGH only when all cited chunks reach the HIGH gate, all strong supplied
evidence is cited and every supporting quote reaches the configured minimum length (12 by default);
otherwise MEDIUM. Source coverage and quote length reduce inflated confidence from selective
citations or trivial matches; they still do not establish semantic entailment.
These gates do not filter the search API or constitute calibrated factual probabilities.
Model sufficiency is a self-report; exact quotes establish provenance, not semantic entailment
of every generated claim. Real-model relevance, adversarial robustness and gate calibration
remain unverified. Milestone 5 adds deterministic retrieval-only regression evaluation separately
from generation, not a live-model production-quality benchmark.

### LLM adapter

The OpenAI adapter uses the Responses API with a strict JSON schema, temperature 0 for GPT-4.1 Mini, bounded
output and `store=false`. It has a fixed HTTPS endpoint, no redirects, bounded connect/request
timeouts, no tools and no automatic retries. Raw REST output is read from the output message
array; refusal becomes insufficient evidence and malformed/incomplete output becomes a safe
502. HTTP/provider errors never expose upstream bodies or keys. LLM configuration is independent
of embeddings, disabled by default and accepts blank legacy provider/model values. Timing logs
contain game/version IDs and durations, not prompts, questions, excerpts, answers or keys.

Optional `LLM_MODEL=gpt-5.6-luna` sends `reasoning.effort` from `LLM_REASONING_EFFORT`
(`none` by application default; also `low`, `medium`, `high`, `xhigh`, `max`) and omits sampling
parameters. Other models retain the existing request behavior and require effort `none`.
The exact Luna alias selects this behavior; compatibility is not inferred from arbitrary model names.
The output budget remains 128–4096 and includes reasoning tokens; truncation fails closed.
Recognized Luna reasoning items with IDs, typed summary arrays and optional completed status are
discarded, never used as rule evidence or stored as conversation messages. Only completed assistant
output text supplies the existing strict answer/citation contract. Refusal, unexpected tool output,
malformed contracts and incomplete responses keep their safe handling. No domain, workflow,
embedding, speech, database or prompt changes are needed. Live Luna evaluation remains unverified.

A limited manual OpenAI walkthrough passed on 2026-10-08 (recorded in DEVELOPMENT_STATUS.md).
Broader provider interoperability, grounding and generated-answer quality evaluation remain deferred.

### Retrieval filters and reranking

`RetrievalFilters` carries normalized language, source types, owned document IDs and explicitly
selected owned EXPANSION document IDs. Filters intersect and cannot alter the selected ruleset.
Language defaults to the version's language; no translation or edition switching is performed.
Unknown/foreign IDs and non-expansion expansion selections fail before embedding calls. Empty
expansion selection excludes expansion sources. This is a future expansion-integration boundary,
not an expansion catalog or persisted session configuration.

The candidate limit defaults to 200 (50–500), similarity threshold to 0.5 (−1 to 1). Filtering
metadata before this limit prevents irrelevant document types from crowding out the requested
sources. No eligible indexed sources means no query embedding request. After thresholding,
`RuleDuplicateSuppressor` retains the first normalized identical text within a matching
version/language/source-type/document-version boundary. Near-contained passages additionally
require the same document, contained source offsets, substantial five-word-shingle overlap
(default 0.85) and the retained passage to contain the shorter candidate. A longer passage is
never dropped in favor of a shorter one. Normalized text/shingles are computed once per candidate.
Source text is not modified and imports are never deleted. Distinct rules and provenance classes
remain separate; duplicate suppression is intentionally conservative.

`RuleReranker` is a provider-neutral permutation boundary after deduplication and before top-K.
The default is deterministic cosine-order preservation. Replacement results must contain the
original candidate records exactly once, retaining original similarity/provenance; invalid
results or exceptions return safe `RERANKING_FAILED` (502). No paid reranker is configured.
Empty eligible pools skip reranking. The bounded pool can underfill top-K after suppression;
`candidateLimitReached` explicitly signals potential omitted later evidence.

Search diagnostics expose effective filters, threshold, pool limit, stage counts and duration.
Timing/count logs omit text, keys and raw provider responses. Evaluation fixtures contain original
Catan fact paraphrases, questions, expected topics/sections and hand-assigned vectors; tests
exercise actual PostgreSQL retrieval with duplicate imports and foreign game/edition decoys,
without LLM calls. The generated build report includes per-question diagnostics, Recall@3, MRR@3
and abstentions. A perfect deterministic score proves pipeline regression behavior only.

### Diagnostics and quote correction

HTTP failures share `code`, `message`, UTC `timestamp`, and request `path`.
MVC advice preserves HTTP status and framework headers; servlet dispatches use the same DTO.
Unexpected exception messages and stack traces are not sent to clients or logged.
Each request gets a generated `X-Request-ID`, also placed in the logging MDC.
Console logs use Spring Boot ECS JSON; database health details remain private.
Rule answer diagnostics log retrieved chunk/document IDs, bounded single-line section titles, pages,
similarity scores, the minimum answer-evidence score and whether each chunk survived the prompt budget.
Every LOW fallback logs a stable reason (missing/weak evidence, prompt budget exclusion, model refusal,
unknown/duplicate/weak citation, quote mismatch or no citations). Existing request-ID MDC correlates these
events. Questions, rule bodies, generated answers/quotes, credentials and audio are not logged by these diagnostics.

Quote mismatches in otherwise valid citations permit one regeneration with identical serialized input
and trusted feedback containing only the rejected chunk ID. Stronger base instructions require contiguous
exact quotes paired with their own excerpt IDs and context-appropriate rules. The entire replacement
response passes the same validation/confidence gates; no content repair, weaker threshold or merging
of old/new answers occurs. Unknown/duplicate/weak citations and model refusal do not retry. The second
attempt cannot retry again. Provider failures propagate before session persistence; only the final
answer/fallback is stored and existing revision guards still apply. Retrieval runs once; a correction can
add one LLM request with its own configured timeout/cost. Logs distinguish attempts without content.

A separate `com.boardgamemaster.rag.quotediagnostics` logger is OFF by default via
`ANSWER_QUOTE_DIAGNOSTICS_LEVEL`. Temporary DEBUG enables local rejected-quote/cited-passage
comparison with attempt and source IDs, plus other prompt chunk IDs containing the exact quote.
JSON escaping preserves whitespace for comparison without multi-line log injection. Passages are
capped at 8000 UTF-16 characters with surrogate-safe truncation and explicit original length/truncation
metadata; quotes retain the answer-contract 1000-character cap. No question/full answer is emitted.
Only this explicit diagnostic can log quote/source text; ordinary INFO diagnostics remain metadata-only.
Captures are private local debugging material, not repository fixtures or proof of semantic correctness.

`SourceQuoteMatcher` permits whitespace-run differences (including line breaks, tabs and Unicode spaces)
within a single supplied chunk. All non-whitespace characters remain literal and ordered; omitted text,
ellipses, changed words/numbers/punctuation and wrong chunk IDs cannot pass. Accepted citations use the
matched original contiguous source substring, preserving paragraph breaks. Generated and restored quotes
are limited to 1000 UTF-16 characters. Whitespace-only matches avoid the correction call; source provenance
still does not establish semantic entailment of the generated answer.
The HIGH-confidence minimum-length gate uses restored source quotes rather than generated whitespace.

## Database, profiles and tests

Flyway owns schema changes; Hibernate uses `ddl-auto=validate` and open-in-view is disabled.
V1 installs pgvector; V2 creates the catalog schema, V3 seeds reference metadata, V4 adds rules,
V5 adds embeddings and their ownership/profile indexes, and V6 adds sessions/teaching/history.
The database account needs extension-creation privileges for V1; production provisioning may
install the extension ahead of time. Production credentials come from environment variables.

The local profile imports optional `.env` as Java properties. Use simple `NAME=value` lines
without shell `export`, quotes, or Compose-only interpolation. Run from the repository root.
The production profile does not import `.env` and disables Swagger/OpenAPI by default.
This bootstrap has no authentication and is intended for local development.

Tests split fast MVC/error checks (`test`) from real PostgreSQL/pgvector startup and HTTP
checks (`integrationTest`). `check` requires both, so unavailable Docker cannot yield a
successful full verification. Neither suite calls paid services.

## Sessions and deterministic teaching

V6 adds `teaching_flows`, `teaching_sections`, `game_sessions` and `conversation_messages`.
Teaching flows belong to a GameVersion and contain ordered uniquely keyed sections with
QUICK/STANDARD/DETAILED applicability, authored explanation, examples, tips and reading pointers.
Publishing validates content for every depth and is append-only; the latest publication is used
for future sessions. A session pins both GameVersion and flow so subsequent default changes or
content publication cannot rewrite its lesson sequence or ruleset. There is no edit/delete API.
Composite foreign keys prevent a session using another version's flow or another flow's lesson.
The current lesson and completed section keys are persisted independently from conversation.

`GameSession` owns deterministic navigation and lifecycle transitions. Sessions start ACTIVE /
TEACHING, with STANDARD depth by default. CONTINUE marks the current section complete and moves
to the next applicable section; PREVIOUS preserves completion history. REPEAT returns stored
content; EXPLAIN_AGAIN presents authored content/examples/tips without an LLM rewrite. Final
CONTINUE enters PLAYING in the same session. Explicit completion records COMPLETED and endedAt;
reads remain available while writes are rejected. Milestone 7 adds SETUP execution and a saved
teaching return position. Setup completion is the user's confirmation, not physical-board verification.

`SessionRepository` encapsulates parameterized JdbcTemplate persistence and immutable record
mapping. The existing JPA transaction manager also binds JDBC work to the same datasource.
`SessionService` validates players through the catalog, selects the default once and creates
the session atomically. Navigation/completion use short row-lock transactions and an expected
revision supplied by the client. Each committed mutation increments the revision; stale or
duplicate actions fail with SESSION_CONFLICT (409), preventing double advancement.

`SessionContextService` loads a consistent repeatable-read snapshot of the session, pinned
game/edition/language, player count, depth, stage, flow and current lesson. It selects a bounded
recent-message window, preserving whole messages in chronological order. Defaults are eight
messages and 8000 content characters, bounded by validated `boardgame.session.*` properties.
Older persisted history remains pageable separately. Oversized newest messages can exhaust
this whole-message policy and leave no retained history; it does not summarize or truncate text.
The service is the boundary for a future summarization policy; no summarizer is implemented.
Session expansion selection is not implemented; existing retrieval defaults exclude expansions.

`AnswerConversation` is a provider-neutral context contract. Retrieval combines the complete
current question first, then the latest retained user question, setup title (during SETUP), lesson
title, setup instruction and lesson explanation. Titles are capped at 160 characters each and the
prior question at 400. Questions of at most 80 Unicode code points with a nonblank prior user question
use 120-character instruction/explanation cues; otherwise those cues retain their 800-character caps.
This length heuristic reduces authored-procedure dominance and prioritizes the preceding user topic
under tight budgets; it does not identify intent or resolve references semantically, and can include
irrelevant history for short topic changes. Prior assistant claims remain excluded from retrieval.
The default path still uses one query embedding and one vector search, without a rewrite-model
call or game-specific rules. The complete structured answer context is not shortened by this policy.
With Unicode-safe truncation, the assembled query respects both the
configured embedding character limit and the 4000-character question limit. This deterministic
topic cue is not a query-rewriting model and does not establish live semantic follow-up quality.

Optional `RAG_QUERY_REWRITE_ENABLED=true` enables `ContextualQueryRewriter` after ownership,
filter/profile validation and an existing-index check. Short session questions (at most 80 Unicode
code points) with retained USER history get at most one `QueryRewriteClient` call; stateless,
long, no-history and empty-index requests skip it. A separate strict Responses schema supplies
`query` and `resolved`; it shares the configured LLM's compatible parameters and response envelope
handling. The last two bounded USER questions, game/edition, stage and authored topic titles are
untrusted rewrite context, never rule evidence; assistant claims and authored instructions are excluded.
Instructions explicitly expand risk/downside into consequence or penalty search terms and omit
unnecessary game/edition framing. They allow consequences later than the current stage without
asserting an unsupported penalty, amount, condition or outcome. Live effectiveness remains unverified.
Resolved valid output (at most 1000 UTF-16 characters and within embedding input bounds, without
control characters) replaces only the embedding/reranking query. Refusal, unresolved/invalid output
or a safe LLM error fall back to the deterministic query, without rewrite retries. The original
question/full context still reach answer generation. No schema, citation gate or version filter is
relaxed; the rewriter cannot change workflow or add source evidence. A model may still distort intent.
The call uses `LLM_*` settings, adds optional cost/latency and has a per-call timeout; no overall
pipeline timeout was added. Outcome/timing logs omit query/history text. The provider boundary is
separate from `LlmClient.generate`, while OpenAI transport/envelope parsing is shared. Disabled
defaults and mocked providers require no paid calls. Live semantic/quality validation remains pending.

Opt-in local `RAG_QUERY_DIAGNOSTICS_LEVEL=DEBUG` enables the separate
`com.boardgamemaster.rag.querydiagnostics` logger (default OFF). After vector search and before
threshold filtering, it records the effective query (1000 UTF-16 character cap, Unicode-safe,
with original length/truncation flag) and up to 500 candidate IDs, bounded section titles, scores
and threshold eligibility. JSON escapes control characters. It omits rule bodies and upstream
responses but deliberately exposes query text; it must be treated as private local debugging.
No additional provider calls, ranking changes or relaxed evidence gates accompany this logger.
`RuleAnswerService.askPinned` validates version ownership without resolving the mutable default.
Optional `RAG_EVIDENCE_REVIEW_ENABLED` (default false) introduces an answer-only candidate policy:
the configured floor/count (0.35/12, bounded to 0–1 and 1–20) replace baseline retrieval and cited
similarity gates for that path. Metadata/version/profile filters, deduplication, bounded reranking
and original scores remain. Ordinary search retains its baseline behavior. `EvidenceReviewClient`
exposes separate strict selection and support contracts through the existing OpenAI transport.
`EvidenceReviewService` selects only valid supplied IDs from the budgeted pool before generation.
After exact source/quote checks and optional quote correction, it checks the answer against only
validated cited quotes plus budgeted question/context; uncited excerpts are removed. Context is
reference-resolution data, not rule evidence. Rejected/malformed/error reviews fail closed to LOW.
Accepted reviewed answers are capped at MEDIUM. No review chooses or changes session state.
The path adds at most two model calls using the same model/settings, with per-call timeouts and
no retries or whole-pipeline deadline. Selection/verification JSON is capped at 32000/40000
characters; pool/budget omissions and correlated same-model errors remain limitations. It does
not prove semantic entailment or establish calibrated confidence. A limited manual walkthrough
answered the risk exchange but exposed incomplete quote-level coverage of an amount/timing claim.
Selection/generation/support instructions now explicitly require quoted premises for each such
detail, with less specific wording or rejection when support is missing. This prompt refinement
adds no calls or deterministic semantic validator. A limited manual retest displayed the precise
scoring citation successfully; broad semantic/citation reliability remains unverified.
The prompt includes structured session/context data as untrusted reference-resolution context;
lesson/setup text and past assistant answers never become source evidence. Existing version/profile
isolation, threshold/deduplication, evidence gates, exact citations and LOW fallback still apply.
The serialized prompt budget drops oldest context turns, then lesson explanation, then setup instruction, then context
metadata as necessary to retain the first retrieved evidence chunk. Evidence itself keeps the
existing whole-chunk budget policy. No LLM chooses workflow state or lesson order.

Questions call providers outside a database transaction. Only after successful generation or a
LOW fallback does a short transaction compare-and-increment the captured session revision and
append the USER/ASSISTANT pair. Both messages, answer citations and the revision commit together
or roll back together. Concurrent session changes discard a stale answer with 409 and no partial
history; provider requests already made can still incur charges. There is no automatic retry.
Conversation ordering uses per-session sequence numbers with a uniqueness constraint; the
session revision update serializes exchange writes. History pages use a before-sequence cursor
and a bounded limit (1–100), returning the selected page oldest first. Completed sessions retain
history but accept no further questions. Logs include IDs/timing and exclude conversation text.

V6 includes original concise reference Catan teaching data (seven QUICK, nine STANDARD, ten
DETAILED sections). It is an introductory overview, not a complete rulebook or verified physical
setup guide. Reading pointers are authored metadata, not evidence citations; the seed does not
import/index rule documents. Generic session/teaching code contains no game-specific branches.
Provider-free teaching and opt-in paid Q&A are documented separately in README. Local APIs have
no authentication, and production authorization/history retention policies remain future work.

Milestone 6 tests cover domain transitions/depth/navigation, bounded structured context, real
HTTP session/flow/history operations, default/flow pinning, grounded follow-ups, cross-session
isolation, provider failures, atomic exchange rollback, concurrent navigation/questions and V5
upgrade preservation. They use isolated PostgreSQL/pgvector containers and mock providers.
The limited manual live walkthrough supplements these automated results; broader quality evaluation
remains deferred. Milestone 8 adds the voice and browser boundaries described below.

## Guided setup (Milestone 7)

`SetupFlow` and ordered `SetupStep` are immutable version-bound authored data with bounded
instructions, notes and warnings. `SetupService` publishes configurations atomically, exposes
current state and delegates navigation to `GameSession` / `SetupProgress`. No game-specific
branch or LLM chooses the next step. One optional teaching-section trigger applies at all depths.
CONTINUE at an unfinished trigger saves the current lesson, enters SETUP and leaves that lesson
uncompleted. Explicit start from TEACHING supports other authored lesson flows.

V7 adds setup_flows/setup_steps and session columns for the pinned setup flow, active step,
confirmed step keys, return lesson and completion flag. Composite FKs enforce ruleset, step
and teaching-return ownership. Progress checks prohibit SETUP without an active unfinished step.
New sessions pin configured setup alongside teaching; legacy sessions choose a setup flow at first
start if needed, then keep it pinned. Navigation and restart lock the session row and compare the
expected revision. Current setup reads use a repeatable-read snapshot. Provider calls remain
outside transactions; question writes compare the captured revision, so concurrent setup changes
cannot append a stale answer or overwrite progress.

NEXT confirms the current step; PREVIOUS preserves confirmations and stops at the first step.
REPEAT preserves state. NEXT cannot leave the last step. COMPLETE requires all preceding steps
and confirms the last, returning SETUP to TEACHING at the saved lesson, never directly PLAYING.
A subsequent CONTINUE advances teaching normally without repeating confirmed setup. RESTART
requires previously started setup and SETUP or TEACHING; it clears only setup confirmations.
Restart during SETUP retains the saved return point; during TEACHING it saves the current lesson.
Session completion at any active stage preserves readable setup/history and prevents further writes.

During SETUP, bounded AnswerConversation context includes the active step key, title and
instruction. That content resolves references but cannot supply rule citations; only retrieved
version-scoped documents are evidence. Prompt budgeting trims setup instruction before discarding
all context and keeps the existing whole-evidence policy.

V7 seeds six original Catan beginner steps with a pointer to the physical beginner diagram. It
publishes a new setup-trigger teaching flow only when the latest Catan flow is the original V6
seed. Old flows/sessions and user publications remain intact; existing documents/vectors/history
are untouched. New reference counts are QUICK 8 / STANDARD 10 / DETAILED 11. No commercial
rulebook files or diagrams are bundled, and authored setup is not automatically indexed. Legacy
non-trigger flows retain their old behavior; readiness does not imply setup was confirmed.

Tests cover generic and Catan journeys, every depth, explicit/triggered start, boundaries/restart,
saved-lesson resume, version/flow pins, Q&A context and failures, stale concurrent requests,
transaction rollback, database ownership and V6 upgrade preservation. They use isolated PostgreSQL
and mocked AI providers. Live setup-specific provider/physical walkthroughs remain unverified.

## Voice and browser demo (Milestone 8)

`SpeechToTextClient` and `TextToSpeechClient` are provider-neutral boundaries with independently
disabled defaults and opt-in OpenAI HTTP adapters. Keys and model settings stay server-side.
STT sends a multipart audio file with a generated filename and receives bounded JSON text;
TTS sends the verified answer text and receives bounded MP3 bytes. Fixed HTTPS production
endpoints do not follow redirects. Safe application errors hide provider response bodies and
credentials. Timeouts are bounded and there are no automatic retries or queues.

`VoiceService` validates MIME/container signatures and byte limits, checks the active session,
captures its revision, transcribes outside a transaction and invokes the existing `SessionService.ask`.
The expected revision is checked before RAG and again by the existing atomic exchange write.
Navigation during transcription therefore cannot silently attach an answer to a changed lesson
or setup step. Questions retain the pinned ruleset, conversation budget and citation validation.
STT, embeddings, generation and TTS never span a database transaction.

Answer failure preserves recognized text in the response without a partial conversation. Synthesis
runs only after the normal text exchange is saved; failure returns the saved text/citations and a
structured audio error. Successful audio is base64 MP3 metadata in the response, played through
a browser Blob URL. Neither uploaded nor generated audio is persisted; V1–V7 remain unchanged
and no new migration is required. Audio signatures are checked without full codec decoding;
server byte limits do not establish duration or transcription quality.

`DemoUiController` is a separate HTML adapter to the existing catalog/session/setup services.
`DemoUiService` builds DTO/read models under a repeatable-read snapshot for pinned content,
workflow state and bounded history. Forms use redirect-after-post and backend revision checks.
Thymeleaf escapes dynamic text; rule source references remain plain text rather than arbitrary
links. Review reads reached lessons/steps without mutation. STANDARD is the initial depth.
The same session moves through TEACHING, SETUP, resumed TEACHING, PLAYING and COMPLETED.

The browser script handles only push-to-talk, upload, playback and presentation. It requests the
microphone on user action, stops tracks on release/cancellation, bounds recordings and displays
transcription, errors and audio. Updated revisions and history come from the backend. Navigation,
rules, retrieval and provider calls remain server-side. The pages use same-origin resources, a
content security policy, no-store headers, cookie-only session tracking and a cross-site form-write
guard. These are local-demo protections, not production authentication or authorization.

Tests exercise multipart provider contracts using local HTTP fixtures, orchestration failures and
race handling, HTML flows/history/citations/escaping and disabled-provider teaching. A headless
desktop/mobile browser walkthrough uses a synthetic microphone and fake providers. Real speech
recognition, audio intelligibility and broader live RAG quality remain unverified; the earlier
manual OpenAI walkthrough and its limitations are preserved in DEVELOPMENT_STATUS.md.

`NarrationService` extends Milestone 8 with spoken authored guidance, independent of STT and RAG.
A repeatable-read snapshot selects the pinned current lesson (including the shared Explain again
presentation) or setup instruction/notes/warnings. A request supplies only revision, presentation
variant and segment index, never arbitrary text. Long content is split into bounded TTS segments;
one segment is synthesized per request outside the transaction, with revision checks before and
after. Narration does not update progress, revisions or conversation history.

The UI offers spoken guidance on the start screen and in active teaching/setup pages. A tab-local
preference enables automatic narration of new content after navigation. Same-content refreshes,
question replies and review returns are suppressed; explicit Repeat/Explain again can replay.
The playback script fetches later segments only as earlier ones finish, supports pause/stop/replay,
reuses current-page audio, aborts pending fetches on navigation and coordinates with question audio
and microphone capture. All spoken content selection and segmentation remain in backend services.

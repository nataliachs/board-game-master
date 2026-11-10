# Board Game Master — Development Status

## Product direction

The main flow is teaching first: select a game, learn the basics, follow physical setup,
resume teaching, then keep the same session open for rule questions during play. STANDARD
is the default depth. Questions preserve the current lesson and setup step.

## Current milestone

**Milestone 8 — Voice + Minimal Demo UI: implemented.** All nine milestones are done.

Since then: Google Cloud deployment, a password-protected admin area, game import from a
rulebook PDF, removal of rules sources, friendly error pages and a scrollable conversation. Current
follow-up work focuses on answer relevance, contextual follow-up questions and citation quality.
The latest full check (2026-11-09) passed **539 tests** (429 unit and 110 PostgreSQL/pgvector
integration tests) with mock providers.

Manual OpenAI tests covered Lantern Trail sessions and a small set of Ticket to Ride questions.
The latest precise-citation retest passed for the reported exchange. Relevance/support review
remains experimental; broader accuracy, repeated-run stability and live speech quality are
still unverified. The dated notes below retain both successful tests and earlier failures.

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

The implementation notes and validation history are separate from live-model evaluation.
Passing tests with mock providers does not establish real-model answer accuracy.

## Separate model for rewriting and evidence review — 2026-11-10

Live timing logs showed answers slowing from 2–6 s (gpt-4.1-mini, revisions 29–30) to 9–29 s once
production used `gpt-5.6-luna` at `medium` effort with evidence review (revision 32 onwards). A
session question now makes up to four model calls in sequence: rewrite 1.7–3.6 s, relevance review
1.4–8.3 s, answer 3–10.5 s and support review 2.4–6.3 s; search stayed under a second. The 29 s case
was the first question after a deploy.

Added optional `LLM_CHECK_MODEL` and `LLM_CHECK_REASONING_EFFORT` (`LlmCheckProperties`): when set,
query rewriting and both evidence-review calls use that model through a second OpenAI client, while
answers and game drafting keep `LLM_MODEL`. Blank keeps the previous single-model behaviour; an
invalid model/effort pair stops startup. `LlmCheckModelTest` covers blank and set models, shared
provider/key/timeout/limit, non-OpenAI clients and configuration binding. Validation: 446 unit and
110 integration tests passed. Not deployed; the intended production value is `gpt-4.1-mini`, and
whether it still rejects the wrong Attack/Nope answer is unverified until retested.

## Withheld answers are logged; session questions also searched alone — 2026-11-10

Production settings (user-approved, not code): `gpt-5.6-luna` since revision 00031; revision
`board-game-master-00032-ljl` raised `LLM_REASONING_EFFORT` to `medium` and turned on
`RAG_EVIDENCE_REVIEW_ENABLED`. Reason: with `low` effort, "A plays Attack, B plays Attack, C Nopes"
was answered "C takes 2 turns" from correct Attack and Nope quotes; by those rules B's Attack never
happened, so B still takes A's 2 turns. Exact-quote validation cannot catch such reasoning errors.
Retest with review on: relevance selection kept Attack (0.49, under the 0.5 baseline cutoff) and
Nope, then the support review rejected the drafted answer (`ANSWER_SUPPORT_REJECTED`, 16 s). The
drafted text was not logged, so a caught error could not be told from a false rejection.

- `Rule answer rejection detail` (reason, question, proposed answer, cited quotes) is now logged on
  the existing opt-in quote diagnostics logger for `MODEL_INSUFFICIENT_EVIDENCE` and
  `ANSWER_SUPPORT_REJECTED`; nothing is logged with the switch OFF.
- Session questions were searched only with the previous question and up to 800 characters each of
  setup instruction and lesson explanation attached (questions over 80 characters never reach the
  rewriter). On the welcome lesson, "Overview of the game" scored level with "Nope" (0.645/0.646);
  on the Defuse setup step, "Setup" outranked Attack and Nope. Unless a rewrite made the question
  standalone, it is now also searched on its own and each passage keeps its better real score:
  one more embedding and vector search, no model call.

`QuoteMismatchDiagnosticsTest`, `RuleRetrievalServiceTest` (two-search merge, plain search once) and
the updated `EvidenceReviewTest` cover this. Validation: 441 unit and 110 integration tests passed.
Not yet retested live.

## Plain-language references for Azul and Exploding Kittens — 2026-11-10

Live retest after PR #27 (revision `board-game-master-00029-lct`): the empty-search retry ran for
every short question and lifted most scores by 0.1–0.3. Dixit and Ticket to Ride, which use
Markdown references with one topic per heading, answered "How do I win?", "What can I do on my
turn?" and "How does the game end?". Azul and Exploding Kittens, imported directly from PDF,
still fell back on some of them with `NO_RETRIEVED_EVIDENCE` (best reworded scores 0.41–0.49):
their passages have no section titles and mix example captions, image labels and, for Exploding
Kittens, rotated cover text into the rules, so no passage matches one topic strongly.

Added `docs/rules/azul-rules-reference.md` and `docs/rules/exploding-kittens-rules-reference.md`:
original plain-language summaries written from the uploaded rule text, one `##` topic per section
(each under 900 characters, so each section is its own passage). Details not stated in the
uploaded text and flagged for the user to check: Azul floor-line values (-1, -1, -2, -2, -2, -3,
-3; consistent with the rulebook's -8 example) and component counts inferred from setup;
Exploding Kittens edition, player count and card total read from fragmented cover text. To use
them, upload each file in /admin and remove the PDF document. Not yet uploaded or retested live.

Known limitation: PDF import keeps no headings and splits by length rather than topic, so other
PDF rulebooks may show the same weak retrieval until the import is improved.

## Short questions found no rules — 2026-11-10

Dixit on the home page, "How do I win?" and "What can I do on my turn?", fell back with
`NO_RETRIEVED_EVIDENCE`; Ticket to Ride and Exploding Kittens showed the same fallback on short
questions earlier that day. With retrieval diagnostics enabled (`RAG_QUERY_DIAGNOSTICS_LEVEL=DEBUG`,
revision `board-game-master-00027-k4m`, kept on at the user's request), "How do I win?" ranked the
right passages first ("End of the game" 0.377, "Object of the game" 0.360) but every score was below
the 0.5 threshold: a three-word question is not similar enough to a rule paragraph. Home page
questions were searched exactly as typed; the existing rewriter only ran for session follow-ups.

Answer searches (home page and session questions) now retry once when no passage passes the
threshold: `ContextualQueryRewriter.expand` restates the question in rulebook wording, without
answering it or adding facts, and the search runs again with the same threshold. The retry cue is
never passed to the answer step. It follows `RAG_QUERY_REWRITE_ENABLED` (on in `prod`), is skipped
when a session follow-up was already rewritten, and costs one LLM call only on a miss. The search
API with explicit `topK`/threshold/filters stays deterministic. `RuleRetrievalServiceTest` covers the
retry, the skip cases, a declined or failed rewrite and the disabled setting.
Validation: 438 unit and 110 integration tests passed. Not yet retested live; whether the restated
Dixit questions clear 0.5 is unverified.

## Azul quotes across bulleted lists were rejected — 2026-11-10

Azul (uploaded PDF rulebook), "what to do on my turn?", fell back with `QUOTE_MISMATCH` on both
attempts although retrieval was good (eight passages, 0.56–0.61). With quote diagnostics enabled
(`ANSWER_QUOTE_DIAGNOSTICS_LEVEL=DEBUG`, revision `board-game-master-00026-gms`, kept on at the
user's request until they ask to remove it), the rejected quote was the page-3 pattern-line rules
word for word, except that the model left out the `•` bullets the PDF extraction keeps before each
list item (`5).\n•\tPlace the tiles`). `SourceQuoteMatcher` now allows one standalone bullet glyph
(•, ◦, ▪, ▫, ‣, ●, ○, ■, □ and the private-use PDF bullets U+F0B7/U+F0A7) between whitespace to be
skipped, and still returns the original span; hyphens, dashes, middle dots, repeated bullets and
bullets attached to words stay literal. Existing documents benefit without re-uploading.
Validation: unit tests passed. Not yet retested live.

Known limitation: the Azul PDF extraction interleaves worked-example captions ("Martin picks the
yellow tile…") with the rule text, which can still weaken answers.

## Session, review, error and admin pages restyled — 2026-11-10

Scope: presentation only; no backend, database, CSP or markup-structure change.

At the project owner's request the "café at night" look from the home page now covers every
page. `demo.css` was rewritten with the home page's palette and self-hosted fonts and its unused
old home-page rules were removed; class names are unchanged, so the templates only gained the
shared logo and navigation in the header fragment (which also fixes its link to the removed
`#library` anchor) and a theme colour. These pages keep motion small on purpose: cards settle in
on load, the current lesson marker pulses, the newest message pops in, all disabled under
`prefers-reduced-motion`. Admin received the same theme through the shared stylesheet and no
page-specific design work. `UI_GUIDE.md` now describes one look for the whole product.

The password fix below was also confirmed on the live site after revision `00023-nz9`: an empty
question posted without credentials returned 400 (validation) where it had returned 401, and
creating a game without credentials still returned 401.

Validation: 432 unit and 110 integration tests passed. Checked in the running local application (AI off)
at desktop and phone width with no horizontal scroll: lesson, setup step, review, error, admin
list, admin editor and add-a-game pages. Not seen in a browser: the playing and completed
states, a conversation with real messages and citations, and the voice and narration panels
(those need AI providers).

## Home page chat no longer asks for the admin password — 2026-11-10

After the redesign was deployed, asking a question in the home page chat opened the browser's
admin login prompt. The chat posts to `/api/v1/games/{gameId}/questions`, and `AdminAccessFilter`
protected every non-GET request under `/api/v1/games/`. The local check missed it because no
admin password is set locally, which leaves the filter open.

`AdminAccessFilter` now leaves exactly `POST /api/v1/games/{gameId}/questions` open; every
catalog and rule write stays protected, and `PaidRequestLimitFilter` still counts the request
(20 per client per hour and 200 per day in `prod` by default). Visitors could already ask paid
questions without a password inside a session, so this is a shorter path, not a new kind of
access. `AdminAccessFilterTest` covers the open path, its near misses and other methods.
Validation: 432 unit and 110 integration tests passed. Not yet verified on the live site, where
the password is actually set.

## Home page redesign from the motion prototype — 2026-11-10

Scope: presentation only; no milestone, backend, database or CSP change. Not committed or
deployed at the time of writing.

`docs/motion-prototype.html` was ported onto the real home page: `home.html`, new `home.css`,
`home.js` and `home-boot.js`; `library.js` was removed. Only the home page changes look; session,
review and admin pages keep `demo.css` (recorded as an exception in `UI_GUIDE.md`).

- Hero: canvas café scene that follows the visitor's local time, with time-of-day buttons,
  floating pieces, pointer and scroll parallax and a letter-by-letter title.
- Shelf: 3D boxes built in the browser from the server-rendered game list, so the games still
  come from the database and the page works without JavaScript. The front face shows the game's
  cover image over a drawn cover (initials and name), which stays when there is no cover or it
  fails to load. Box colours and sizes are derived from the game name because the catalog stores
  neither. Filters use the real player counts and duration. The panel under the shelf is the
  existing start form (players, teaching style, read aloud) posting to `/ui/sessions`.
- Track: scroll-driven meeple over the real flow (pick, learn, set up, play and ask). The
  prototype's score-counting step was dropped because the product has no such feature.
- Ask the host: questions go to the existing `POST /api/v1/games/{gameId}/questions` for the
  game pulled from the shelf; switching games starts a new conversation. Nothing is sent until
  the visitor asks. Answers show the evidence level and source quotes. The prototype's sample
  conversations were removed.
- Because the page CSP allows only same-origin scripts, styles and fonts, there are no inline
  styles or scripts and the three fonts (SIL OFL 1.1) are self-hosted in `static/fonts`.
- Fixed while porting: the prototype blended time-of-day colours incorrectly after the first
  switch, and a pulled-out box hid its neighbours on a short shelf.

Validation: `gradlew test` passed 431 unit tests, including the new `HomeTemplateTest`, which
renders `home.html` without a database, and `gradlew integrationTest` passed all 110
PostgreSQL/pgvector integration tests (541 in total, mock providers).

Browser checks: the page was first reviewed at desktop and phone width (no horizontal scroll)
against a local stand-in server serving the rendered template, the real static files, the
application's CSP header, five test games and a mocked question endpoint. It was then opened in
the running application (`bootRun`, local database, all AI providers disabled): the shelf showed
the one local game with its drawn cover, there were no console or CSP errors, a chat question
reached the real endpoint and showed the "host can't answer right now" message, and Start game
opened the first lesson. Not verified: a real AI answer in the home page chat (no paid call was
made), real cover images inside the running application (the local game has none; they were
seen only on the stand-in), reduced-motion behaviour and the no-JavaScript fallback.

## Live retest after all answer fixes — 2026-11-09

Deployed revision `board-game-master-00019-2js` (PR #20 on top of #17–#19, with `RAG_TOP_K=8`).
The project owner retested Exploding Kittens, including the Defuse follow-up conversation, and
reported the results as correct. This is a manual check on one game, not a benchmark; the
intermittent `QUOTE_MISMATCH` seen once on "How many cards can I play on my turn?" was not
diagnosed further, and Dixit has not yet been retested on this revision.

## The answer step now sees the resolved follow-up — 2026-11-09

Retest on revision 00018 (PR #19 deployed): "Will it ever go back to the draw pile?" after a
question about the played Defuse again got "Yes" about the Exploding Kitten. The logs showed the
follow-up rewrite applied, but its result was used only for the search: the answer model still
received "Will it ever go back…", saw passages about reinserting the Kitten, and answered about
that. A prompt instruction alone did not change this.

`RuleRetrievalService.SearchResult` now carries `resolvedQuestion` (the rewrite, or null), and
`RuleAnswerPromptBuilder` adds it to the answer input as a labelled field, with an instruction
that it restates the current question and is neither evidence nor a new question. This reverses
one point of the earlier design, which kept the rewrite out of the answer prompt entirely; the
other guarantees stand: the user's own question is still the one answered, the rewrite is never
an excerpt and can never be cited. `RuleAnswerServiceTest` (renamed test), `RuleRetrievalServiceTest`
and `AnswerCompletenessConfigTest` cover it. Validation: 429 unit and 110 integration tests passed.

Still open: "How many cards can I play on my turn?", asked as the fourth question in that session,
fell back with `QUOTE_MISMATCH` on the same page-1 passage even with plain-punctuation excerpts,
so at least one more kind of quote difference exists. Asked as a first question it succeeded
five times out of five.

## Garbled apostrophes in quotes; follow-ups about the wrong card — 2026-11-09

Exploding Kittens kept falling back with `QUOTE_MISMATCH` on different page-1 and page-2 passages.
With quote diagnostics enabled briefly (revision 00016, removed in 00017), "How many cards can I
play on my turn?" was asked five times as a player in one test session: all five answered
correctly, and one first attempt was rejected and then corrected. Its rejected quote was exact
except that the rulebook's curly apostrophe in "you’d" came back as a control character followed
by "9d". The model garbles typographic punctuation when copying it.
`RuleAnswerPromptBuilder` now sends excerpts with plain ASCII quotes, apostrophes and hyphens
(one character for one, so offsets are unchanged); `SourceQuoteMatcher` already treats them as
equal and returns the stored text, so citations still show the original rulebook wording.

In the same conversation, "will it ever go back to the draw pile?" after a question about the
played Defuse was answered "Yes" about the Exploding Kitten from the previous answer. The prompt
now says that pronouns refer to what the user asked about in their previous question, not to
something mentioned only in an assistant answer, and to decline rather than answer about a
different card or action.

Side finding: in `prod`, the stateless question API (`POST /api/v1/games/{id}/questions`)
requires the admin password, because `AdminAccessFilter` protects every POST under
`/api/v1/games/`. Player sessions are unaffected; this keeps anonymous paid calls off that API.
Diagnosis left one test session in the production database.

`AnswerCompletenessConfigTest` covers the plain-punctuation excerpts and the follow-up rule.
Validation: 428 unit and 110 integration tests passed. Not yet retested live.

## Two quotes from one passage were rejected — 2026-11-09

Exploding Kittens, "How many Defuse cards do I start with?", fell back with `DUPLICATE_CITATION`
although retrieval was strong (eight passages, best 0.79 on page 1). The model cited the same
passage twice, most likely with two different quotes: the setup passage holds both "deal 1 to each
player" and "7 cards + 1 Defuse". The original design rejected any second citation of a passage.
Now different quotes from one passage are each validated and shown, and an exact repeat of the
same passage and quote is shown once; unknown passages, weak evidence and mismatched quotes
still fail. The prompt says a passage may be cited more than once with different quotes.
`RuleAnswerServiceTest` covers the exact repeat, two valid quotes, and a second quote that
doesn't match. Validation: 426 unit and 110 integration tests passed. Not yet retested live.

## Live diagnosis on Cloud Run — 2026-11-09

With read access to the project's Cloud Run logs:

- **Dixit, "What if nobody finds the storyteller's card?"**: the five retrieved passages were
  Overview, Voting, Other players choose a card, A round of play and Giving a good clue; "Scoring a
  round", which holds the complete rule, was not among them. The deployed service now uses
  `RAG_TOP_K=8` (revision `board-game-master-00013-q8r`); the code default stays 5.
- **Dixit follow-up**: the rewrite applied and "Scoring a round" was the top match (cosine 0.69),
  but both quote attempts were rejected: the bold-text problem below.
- **Exploding Kittens**: one rulebook document, imported after the column fix, with clean text.
  An earlier `QUOTE_MISMATCH` on a page-1 passage did not recur: with quote diagnostics enabled
  temporarily (`ANSWER_QUOTE_DIAGNOSTICS_LEVEL=DEBUG`, revision 00011, removed again in revision
  00012), both retested questions were answered on the first attempt. The cause of the earlier
  mismatch is unconfirmed; capitalisation of "YOU WIN" is a candidate.

## Bold rule text could not be quoted — 2026-11-09

Retest after the change below: the first answer was still storyteller-only, and the follow-up
now fell back with `QUOTE_MISMATCH`. The logs showed the first answer used "Object of the game"
(cosine 0.64), and the rewritten follow-up reached a different passage, which the model quoted
but which failed validation. The Dixit rules summary writes the key scoring cases in Markdown
bold (`**Everyone found the storyteller's card, or nobody did:**`); models quote that sentence
without the asterisks, so the passage that holds the complete answer could never be cited.
`SourceQuoteMatcher` now skips `*`/`_` emphasis runs (one to three characters) at the start or
end of a word and still returns the original span; markers inside words, longer runs and every
other character stay literal. Existing uploaded documents benefit without re-uploading.
Validation: 425 unit and 110 integration tests passed. Not yet retested live.

## Incomplete answers and misread follow-ups in Dixit — 2026-11-09

Live test on the deployed app. "What if nobody finds the storyteller's card?" was answered with the
storyteller's score only, leaving out that every other player scores 2. The follow-up "how about
other player?" was answered about choosing cards: the search ran on the follow-up alone.

- The answer prompt asked for concise answers but never for complete outcomes. It now tells the
  model to give every outcome the cited excerpts state for each affected player or role, and
  that concise means short, not incomplete.
- Contextual follow-up rewriting already existed (`ContextualQueryRewriter`) but was off by default.
  The `prod` profile now turns it on (`RAG_QUERY_REWRITE_ENABLED`, default `true` there, still
  `false` locally); it adds one small LLM call per short follow-up and can be switched off.

`AnswerCompletenessConfigTest` pins both. `gradlew.bat check bootJar` passed: 423 unit and 110
integration tests. Not yet retested against the live model; both questions should be asked again
after deployment.

## PDF answers rejected as QUOTE_MISMATCH — 2026-11-09

First live test of an imported rulebook (Exploding Kittens): a basic question ("How do I win?")
fell back with `reason=QUOTE_MISMATCH`. Two causes, both fixed:

- `PdfRuleExtractor` sorted text by position, which reads straight across multi-column pages and
  splices unrelated columns into one line (confirmed on the Dixit rulebook), so a clean sentence
  quoted by the model did not exist in the stored text. It now keeps the PDF's own text order.
  A two-column test PDF checks this; it fails with the old setting.
- `SourceQuoteMatcher` required identical punctuation, while rulebooks use curly quotes,
  apostrophes and en/em dashes and models often write straight ones. Those variants now match
  each other; everything else stays literal, and the returned quote is still the original span.

Rulebooks imported before this fix keep their old stored text; the punctuation fix applies to them
at once, the column fix only to newly imported or uploaded PDFs. Extracting the Exploding Kittens
rulebook locally confirmed the cause: the old order produced "…there's only for example 1 player
left who wins the game. For a 4 player game, insert 3 Kittens." (two columns spliced), while the
new order keeps "…until there's only 1 player left who wins the game." intact. Not yet confirmed
with a live answer.

**Removing a rules source.** Section 4 of the game editor has a **Remove…** fold-out per source,
with a required confirmation tick. `RuleEmbeddingService.remove` deletes the source's vectors,
passages and document in one transaction (`RuleVectorStore.deleteDocument`, then
`RuleDocumentService.deleteDocument`); answers already in conversations keep their copied quotes.
Games themselves still can't be deleted. `GameImportIntegrationTest` covers the missing
confirmation, removal of the document, passages and a vector, the other source surviving, and a
repeated removal. The fold-out was checked in the browser.

Validation: `gradlew.bat check bootJar` passed: 421 unit and 110 integration tests.

## Import a game from a rulebook PDF — 2026-11-09

`/admin → Add a game` has an **Import from a rulebook PDF** card. `GameImportService` extracts the
PDF's text page by page (`PdfRuleExtractor`, Apache PDFBox 3.0.4, with `<!-- page: N -->` markers),
asks the LLM for one structured draft (`GameDraftClient`, strict JSON schema `game_teaching_draft`
on the OpenAI adapter), fits it to the app's rules (styles nest Quick ⊂ Standard ⊂ Detailed, one
setup-starting lesson shown in all styles, lengths and player counts bounded), and saves it in one
transaction through the existing `AdminUiService` checks: a **hidden** game with its default
version, setup steps, lessons and the rulebook as a rule document. Indexing runs afterwards; if it
fails, the import still succeeds and says so. Nothing is saved if extraction or drafting fails.

Design decisions (agreed with the project owner):

- **Review before players see it.** Drafted content is application-managed data reviewed by the
  admin, consistent with the spec's rule that teaching structure is stored data and "the LLM must
  not invent rules". The game stays hidden until the admin ticks *Show this game to players*.
- **The rulebook text itself is indexed for questions**, not an AI summary, so answers quote real
  rules and citations show page numbers. It stays in the private database, never in Git; the form
  requires confirming the PDF is the admin's own copy for private use.

The draft call has its own limits (`GAME_DRAFT_MAX_OUTPUT_TOKENS`, default 16000;
`GAME_DRAFT_TIMEOUT`, default 180s) because a whole game is far longer than a rule answer. The
prompt treats the rulebook as data, asks for original beginner-friendly wording, base game only,
and the rulebook's language. The regular rules upload in section 4 now accepts PDFs too.

Validation: `gradlew.bat check bootJar` passed: 418 unit and 110 integration tests. New tests:
`PdfRuleExtractorTest`, `GameImportServiceTest`, OpenAI draft request/response and configuration
cases in `OpenAiLlmClientTest`, and `GameImportIntegrationTest`, which uploads a generated PDF
through the admin page with a stubbed draft client and checks the hidden game, steps, lessons,
paged chunks and error cases. Extraction was also checked on a real 4-page rulebook PDF (10
passages, one or more on every page). **Not yet verified:** a live draft from the real model, so
draft quality, length and timing on real rulebooks are unknown. Scanned PDFs are rejected rather
than read with OCR.

## Scrollable conversation box — 2026-11-09

Long sessions made the session page very long because every question and answer stacked below
the Game Master panel. The conversation now sits in a fixed-height box (up to 62% of the screen
height, at most 640 px) that scrolls by itself, is keyboard-focusable and is marked as a log for
assistive technology. Presentation-only `conversation.js` opens it at the newest message; on an
"Older messages" page it stays at the top, and the voice panel calls it again after refreshing
the conversation. "Older messages" moved above the box, matching the chronological order.

Validation: checked in the browser at 1024 px and 375 px with 24 messages against a throwaway
database (20 shown, box opens at the newest answer, no horizontal scrolling, older page loads).
`SessionIntegrationTest` now checks the box, the script and the older-messages page.
`gradlew.bat check bootJar` passed: 395 unit and 109 integration tests. The spoken-question
refresh path was not exercised in the browser because speech providers were off.

## Friendly page when a browser page fails to render — 2026-11-09

When a page template failed while rendering, Tomcat forwarded to `/error`, whose controller only
returned JSON. The failed page had already marked the response as HTML, so Spring could not write
the JSON either and the browser received an empty 500 page. `ApiErrorController` now renders the
existing `error` template for requests that accept HTML, with fixed wording per status (servlet
messages and exception text never reach the page), and sets `application/json` explicitly for
everyone else.

Validation: new `ApiErrorControllerTest` cases and a `SessionIntegrationTest` check of `/error`
as a browser and as a JSON client. The original failure was reproduced against a throwaway
database by temporarily reintroducing a template error: a browser received the friendly
"Let's try again" page (500, `text/html`) and a JSON client the `INTERNAL_ERROR` body; the template
was then restored. `gradlew.bat check bootJar` passed: 397 unit and 109 integration tests.

Unknown addresses (for example `/no-such-page`) are answered by Spring MVC before `/error`.
`BrowserNotFoundHandler` now gives requests that accept HTML the same friendly page with a 404
message; `/api/` paths and clients that do not accept HTML (JSON, scripts, missing CSS or images)
keep the JSON `NOT_FOUND` body. Tests: `BrowserNotFoundHandlerTest` and `SessionIntegrationTest`
(a browser, a JSON client and an API path). `gradlew.bat check bootJar` passed: 399 unit and
109 integration tests.

## Admin and rate-limit path bypass fix — 2026-11-09

`AdminAccessFilter` and `PaidRequestLimitFilter` matched on the raw `getRequestURI()`, while
Spring MVC routes on a decoded path without `;parameters`. Requests such as `/admin;x`,
`/%61dmin`, `//admin` or a POST to `/api/v1/games;x` skipped the password check yet reached the
protected controllers, and similar paths skipped the AI request caps. Both filters now match on
`RequestPaths.normalized` (Spring's `UrlPathHelper`: decoded, path parameters removed, repeated
slashes collapsed). New unit tests send disguised paths through each filter; they fail against
the old raw-URI check and pass with the fix.

Known limitation: with `forward-headers-strategy: framework`, the per-client hourly cap keys on
the leftmost `X-Forwarded-For` value, which clients can set, so it can be evaded. The app-wide
daily cap and a spending limit at the AI provider are the effective guards.

Validation: `gradlew.bat check bootJar` passed and built the JAR: 395 unit tests and 109
PostgreSQL/pgvector integration tests (forced rerun), zero failures or errors.

## Google Cloud deployment support — 2026-11-07

Added a two-stage `Dockerfile` (Temurin 21, non-root runtime), `.dockerignore`, `.gcloudignore` and
[DEPLOY_GCP.md](DEPLOY_GCP.md) for Cloud Run with Cloud SQL for PostgreSQL 17 (pgvector) and
Secret Manager in `asia-southeast2`. The Cloud SQL JDBC socket factory (1.30.2) is a runtime
dependency, used only when `DB_URL` names a `cloudSqlInstance`. The `prod` profile now trusts
forwarded headers, requires an admin password, and caps AI requests.

`AdminAccessFilter` puts HTTP Basic auth on `/admin` and on content-changing APIs under
`/api/v1/games` and `/api/v1/game-versions`; without a password it stays open locally and refuses
those requests in `prod`. `PaidRequestLimitFilter` caps question, voice and narration requests per
client per hour and per day (in memory; off unless configured). Unit tests cover both filters.
Not deployed. The build and tests were later run locally (see the current milestone summary).

## Local admin area — 2026-11-07

Added `/admin` pages (Thymeleaf, no JavaScript) for adding a game and its default version, editing
game details and covers, writing setup steps and lessons, and uploading and indexing `.txt`/`.md`
rule sources. `AdminUiService` only translates form rows into the existing catalog, setup, teaching
and rule services, so publishing stays append-only and existing sessions keep their pinned flows.
Friendly validation keeps typed rows after a rejected save. `Game.updateDetails` and
`GameService.updateGameDetails` add metadata editing; the slug stays fixed. Admin pages use the
same CSP, no-store and cross-site POST protection as the other demo pages. `AdminAccessFilter`
(see Google Cloud deployment support) adds HTTP Basic auth when `ADMIN_PASSWORD` is set; without
it the admin area is open locally and refused in `prod`. Instructions: [admin guide](ADMIN_GUIDE.md).

Tests: `AdminUiServiceTest` (slugs and keys) and a browser-level admin journey in
`BootstrapIntegrationTest` (create, setup, a rejected lesson save, lessons, then a playable session).
These passed in the 2026-11-07 local run.

## Daylight café UI, game library and Ticket to Ride reference — 2026-11-07

Restyled every page (home, session, setup, review, error) with the "Daylight café" palette:
espresso header, terracotta primary actions, honey accents and a stage tracker. The home page
is now a game library: searchable cards, category and player-count filters (presentation-only
`library.js`, with a no-JavaScript fallback), and a side panel for players, teaching depth and
start. The session page shows the learning path as a track of spaces, the lesson or setup card,
and a Game Master panel holding questions, voice and the conversation. All element IDs used by
`narration.js` and `voice.js` are unchanged, and every style remains in `demo.css` for the CSP.

Catalog changes: V8 adds optional `category` and `cover_image_url` columns (https:// URL or a
path starting with `/`), exposed in the catalog API; cards without a cover show generated
initials. The CSP now allows `img-src 'self' https:` for remote covers. V9 makes Ticket to Ride
(2025 Edition) the reference game: it deletes the Catan seed and anything attached to its version
(sessions, history, rule documents, chunks, vectors), keeps other games, and seeds Ticket to Ride
with twelve lessons (QUICK 9, STANDARD 11, DETAILED 12) and seven setup steps. The text is original
beginner guidance checked against the 2025 rules booklet; no rulebook is bundled or indexed.

Tests: reference-game tests now use Ticket to Ride IDs, player limits and lesson/step counts.
Upgrade tests written for V4–V7 stop at V8 so they still prove those migrations preserve data;
a new test covers V9 removing Catan while keeping a user-registered game. The retrieval
evaluation keeps its `catan-rule-questions.json` fixture, now attached to new versions of the
reference game.

Validation: V2–V9 ran in one transaction each against a temporary PostgreSQL 16 database with a
stand-in for the pgvector table, including an existing Catan session, message and a second game;
V9 removed the Catan data, kept the second game and produced the expected counts. Hand-rendered
copies of the home and session markup were checked in headless Chrome at 1366 px and 390 px.
The Java build and test suites were not run here: Gradle could not be downloaded and Docker is
unavailable. Run `.\gradlew.bat check bootJar` locally before merging.

## Session UI clarity follow-up — 2026-10-27

Added [UI guide](UI_GUIDE.md) for the teaching-first browser journey, screen priorities,
visual rules and acceptance checks. The session template now shows lesson/setup progress
as a labeled visual indicator, marks the current learning-path item for assistive technology,
and gives the next step a clearer action group. CSS compacts the learning path on narrow
screens and separates answer evidence from lesson content. Backend workflow, API and
provider behavior were not changed.

Validation: `git diff --check` passed; the guide has no trailing whitespace, and the
template contains the expected two progress indicators and two action groups. Browser
rendering and automated Java tests were not run in this environment: the available Java
command did not complete, and the local application port was inaccessible.

## Portfolio presentation and fresh local check — 2026-10-09

Added an actual screenshot of the running browser start screen, README navigation, engineering
decisions and tradeoffs, and a quick start with explicit process-only overrides disabling all
AI providers. Added `docs/DEMO.md` with a short recording guide and a separate optional
cited-answer segment. No sessions were created or changed while capturing the start screen.

Reviewed the existing GitHub Actions workflow: it runs `check bootJar` on PRs targeting `main`,
pushes to `main` and manual dispatch, with Java 21 and Docker. No workflow change was needed.
The README badge links to that workflow and tracks `main`. The public GitHub API returned 404
for workflow runs, so remote run success could not be verified; the badge is not evidence that
the current uncommitted working tree passed CI.

Validation: `gradlew.bat check bootJar --rerun-tasks --no-daemon --console=plain` passed with
376 unit tests and 107 PostgreSQL/pgvector integration tests (483 total, zero failures, errors
or skips), and built the executable JAR. Tests used mock providers and isolated databases.
Markdown file links and heading anchors resolve; `git diff --check` passes. The screenshot
was visually inspected. Existing live walkthrough results and broader evaluation limitations
remain unchanged. No paid API calls, private `.env` edits, live-database writes, commits or pushes.

## Documentation wording review — 2026-10-09

Reviewed all six Markdown files. Shortened repeated introductions, clarified each document's
purpose, and rewrote conversational development notes as a test history. Commands, fenced
examples, demo rules and the recorded live-test results and limitations are unchanged.

Validation: Markdown file links and heading anchors resolve; all existing fenced blocks match
the pre-edit copies; the demo rules are unchanged; recorded history retains the same numeric
values. Non-Markdown file hashes are unchanged and `git diff --check` passes. Application tests
were not rerun for this documentation-only change; the 483-test result above is from the earlier
implementation check. No paid API calls, database changes, commits or pushes.

## Optional Luna adapter follow-up — 2026-10-08

Scope: optional answer-model support on `feature/luna-model-support`; no new milestone,
workflow, UI, database migration, embedding or speech change. GPT-4.1 Mini remains the default
and retains temperature 0 without a reasoning parameter. Providers remain disabled by default.

- Exact model `gpt-5.6-luna` sends Responses `reasoning.effort` and omits sampling parameters.
  `LLM_REASONING_EFFORT` defaults to `none`; supported Luna values are `none`, `low`, `medium`,
  `high`, `xhigh`, `max`. Invalid values and non-`none` effort for other models fail startup.
- Completed responses may contain recognized reasoning items before/after the assistant message.
  Those items are validated and discarded; only assistant output text enters the existing strict
  answer/citation contract. Reasoning is not rule evidence or session history. Refusals remain
  insufficient evidence; incomplete output, invalid contracts and unexpected tools fail safely.
- Existing strict JSON schema, `store=false`, fixed endpoint, timeout, token budget and citation/
  confidence validation remain in effect. Output is bounded to 128–4096 tokens including reasoning;
  higher effort can exhaust this budget or timeout. No automatic retry or model fallback was added.
- README, architecture notes, application configuration and `.env.example` document switching
  models, effort, budget limits and rollback. The private `.env` was not read or modified.

Validation: `check bootJar` passed with 263 unit tests and 107 PostgreSQL/pgvector integration
tests (370 total, zero failures/errors/skips), using local HTTP provider fixtures/doubles and
disposable databases. After adding four more response edge-case fixtures, the final `test` run
passed with 267 unit tests. Combined validated coverage is 374 tests (267 unit + 107 integration),
zero failures/errors/skips. `git diff --check` passed. Production code was unchanged after the
full build/integration run.

Limitations: no paid API calls or live Luna evaluation. Luna answer quality, grounding, latency,
cost and practical reasoning budgets remain unverified. Model switching does not resolve the
previously recorded broader RAG, quote-correction or speech evaluation limitations. Historical
live OpenAI walkthrough results and earlier test history below are preserved. No commit or push.

## Follow-up question investigation — 2026-10-09

Manual Ticket to Ride browser testing after selecting Luna produced cited answers for
keeping two initial tickets, returning initial tickets, and keeping five (the last answer had a
misleading affirmative opening before explaining the initial-deal restriction). A customer-service
phone-number question returned the expected LOW/no-evidence fallback. After the output budget was increased to 4096, “What if I keep only one?” received “Yes” followed by
an explanation about keeping two. This is a failed relevance/consistency case despite MEDIUM
evidence confidence. Actual active model/effort settings and provider telemetry were not independently
verified from the screenshots; these observations come from manual testing, not a benchmark.

Inspection found that the correct current question was serialized before prior conversation turns.
The generic prompt now places that question last and explicitly prioritizes its quantities,
negations and conditions over earlier turns; it asks for a direct answer in the active situation
and a consistency check between the opening and explanation. No game-specific answers, extra
provider calls, semantic verifier, confidence calibration or workflow changes were added.

An original fictional-rule regression fixture checks the one-after-two exchange's current question,
history separation, evidence preservation and quote-correction input. Mock providers verify
prompt construction and integration only; they cannot establish that a real model follows these
instructions. A live repeat of this exchange remains pending. Prior live results and broader
grounding/evaluation limitations remain in effect. `check bootJar` passed with 268 unit tests
and 107 PostgreSQL/pgvector integration tests (375 total, zero failures/errors/skips), using
mock providers and disposable databases. `git diff --check` passed. No paid API calls,
private environment edits, commit or push.

### Explicit quantity deductions — 2026-10-09

After the prompt adjustment, manual testing showed a LOW fallback for “Can I start with five tickets?”.
The supplied log contains one fallback, requestId `bc397016-2560-4249-89b0-5b7bfad76905`, with
`MODEL_INSUFFICIENT_EVIDENCE`; five chunks were included, with the setup chunk ranked first
(similarity approximately 0.647). Questions are not logged, so the screenshot/request mapping
is inferred from the manual test report. The supplied setup chunk's content
confirms it specifies an initial deal of four tickets and retaining at least two. This supports
a generation sufficiency problem for this case, rather than missing retrieval or quote rejection.
The same log contains a different request with quote correction accepted; it is not the fallback.

The generic prompt now explicitly permits direct conclusions from stated quantities/conditions,
such as keeping items from a fixed initial deal, while requiring actual supporting rule quotations
and forbidding missing premises, unstated actions or exceptions. No game-specific production
rules, relaxed citation checks, retrieval-threshold changes or additional provider calls were added.
Four original fictional-rule mock-provider cases cover quantities below the minimum, at both
bounds and above the initial deal. They verify prompt delivery, accepted source provenance and
one-call orchestration; mock answers do not prove semantic inference by a live model.

Validation: `check bootJar` passed with 272 unit tests and 107 PostgreSQL/pgvector integration
tests (379 total, zero failures/errors/skips), using mock providers and disposable databases.
`git diff --check` passed. Live repeat with this deduction instruction,
broader semantic correctness, model/effort telemetry and previous evaluation limitations remain
unverified. Historical walkthroughs are preserved. No paid API calls, commit or push.

## Short contextual follow-up retrieval — 2026-10-09

Subsequent user screenshots show successful limited retests of initial quantities (one, two/four,
five), returning unwanted initial tickets and the unsupported phone-number fallback after the
quantity prompt change. A later screenshot also shows resistance to an instruction to ignore
the rules and permit five tickets. Sources were collapsed in screenshots, so citation content,
unchanged workflow state and actual active model/effort were not independently verified.
These observations supersede pending live retest for these individual cases only; they do not
establish broader correctness or calibrated confidence.

The short follow-up “What's the risk?” after keeping all four still returned LOW. The supplied
log's fallback request `4eec7136-db72-4e51-aa7b-c1b93d6c40b5` shows four prompt chunks and
`MODEL_INSUFFICIENT_EVIDENCE`, without Game End (question text is not logged, so request mapping
is inferred). The supplied Draw Tickets content lacks the penalty; separately supplied Game End
content contains it. This confirms missing relevant evidence in that request, not quote rejection.

Retrieval cues now prioritize the previous user question immediately after the current question,
then short topic titles before detailed guidance. With a nonblank prior user question and a current
question of at most 80 Unicode code points, setup instruction and lesson explanation cues are
capped at 120 characters each instead of 800. No-history initial questions and longer questions
retain 800-character cues. Existing 400-character prior-question, 160-character title, overall
embedding-input and Unicode-safe bounds remain. The generator still receives full structured
context subject to its existing budget. Assistant-generated history is not used in retrieval cues.

This is a deterministic length heuristic, not intent recognition or semantic query rewriting.
It may carry an irrelevant prior topic into short unrelated questions; it does not guarantee that
the penalty will rank above threshold. No new provider calls, lower thresholds, additional vector
searches, game-specific logic or citation relaxations were added. Regression tests cover cue balance,
tight budgets, no-history/long-question behavior and a single embedding/search via mock providers.
Validation: `check bootJar` passed with 275 unit tests and 107 PostgreSQL/pgvector integration
tests (382 total, zero failures/errors/skips), using mock providers and disposable databases.
`git diff --check` passed. Live risk-follow-up retest and broader retrieval
quality remain unverified. No paid API calls, private environment/database changes, commit or push.

Manual retest after this change still failed for the risk follow-up. The supplied screenshot
shows the correct all-four setup answer followed by LOW for “What's the risk?”. Log request
`9d4a5d13-f2eb-4880-a2ec-e05b4fb99c7f` includes setup, game turn, draw tickets, components and
draw train cards, with `MODEL_INSUFFICIENT_EVIDENCE`; Game End is absent again. The log records
16 candidates, 11 below the retrieval threshold and five returned. Given the supplied Game End
chunk is in this indexed document, it appears to remain below threshold rather than being lost
to top-K truncation, though its individual score is not logged. The cue-shortening heuristic
therefore has not solved this live case. Treat it as a failed live retrieval retest despite passing
automated contracts; no paid calls or further source changes were made for this diagnosis.

## Optional contextual query rewriting — 2026-10-09

Authorized follow-up to the failed short-risk retrieval retest; no new milestone was started.
`RAG_QUERY_REWRITE_ENABLED` defaults to false. When enabled, indexed session questions of at
most 80 Unicode code points with retained USER history can make one rewrite call before query
embedding. Initial/no-history, longer and stateless questions and empty indexes skip it.

`QueryRewriteClient` is a provider-neutral boundary separate from answer generation. The OpenAI
adapter shares transport/model compatibility and envelope parsing, with a distinct strict
`standalone_rule_query` schema (`query`, `resolved`). It uses existing `LLM_*` model, reasoning,
timeout and output settings, `store=false`, no tools, no redirects and no rewrite retries.
The rewrite receives at most two 400-character prior USER questions, bounded game/edition and
topic titles, stage and current question. Prior assistant claims and authored instruction bodies
are excluded. Instructions permit resolving references/searching consequences, but forbid answering,
inventing facts/outcomes or changing explicit quantities, conditions, game or edition.

Only a resolved, valid query of at most 1000 UTF-16 characters within the embedding limit, without
control characters, replaces the embedding/reranking query. Refusal, unresolved references,
invalid output and safe provider failures retain the deterministic query. The generator still
receives the original question and existing context; rewrites never enter citations or stored
messages. Existing version/profile/ownership filters, thresholds, evidence gates and workflow
remain unchanged. Logs report session ID, outcome and timing without question/rewrite/history text.

Enabling can add one paid LLM call and per-call timeout before the usual embedding/answer calls;
quote correction may independently add its existing answer call. Short standalone questions may
incur a rewrite call before the rewriter declines to change them. No whole-pipeline timeout or
semantic validation of the generated search query was added. Live rewrite quality, actual retrieval
of the missing penalty, latency and cost remain unverified. The preceding cue-shortening live test
remains failed; this optional path is not claimed to fix it until a new manual live retest.

Mock-provider coverage includes separate Responses contracts for Mini/Luna, refusals/malformed
rewrite output, disabled/long/no-history skip paths, empty-index skip, provider-error fallback,
single embedding/search orchestration and original-question/source preservation through generation.
Validation: `check bootJar` passed with 295 unit tests and 107 PostgreSQL/pgvector integration
tests. After adding the original-question-through-generation regression fixture, the final `test`
run passed with 296 unit tests. Combined validated coverage is 403 tests (296 unit + 107 integration),
zero failures/errors/skips. Production code was unchanged after the full build/integration run.
`git diff --check` passed. README, architecture notes and `.env.example` document opt-in,
diagnostics, cost and rollback. The private `.env` and database were not changed. No paid calls,
commit or push were made.

### First live rewrite retest and local diagnostics — 2026-10-09

The screenshot and log from manual testing still show LOW for the risk follow-up. Request
`d2cf914d-d2e3-4ce3-9c16-a5d8684d6bb9` reports rewrite `APPLIED` (~2336ms), then 16 candidates,
12 below threshold, four returned: introduction, components, setup and game turn. Game End is
absent; the answer fallback is `MODEL_INSUFFICIENT_EVIDENCE`. The introduction ranks first at
approximately 0.685. Rewriting executes but has not solved this live retrieval case. The log
does not contain the generated query or Game End's score, so query quality and its exact rejection
score cannot yet be assessed. This supersedes pending live testing for execution of the optional
path only; its quality remains failed/unverified and earlier evaluation limits remain in effect.

Added default-OFF local diagnostics via `RAG_QUERY_DIAGNOSTICS_LEVEL=DEBUG`: effective query
(at most 1000 UTF-16 characters with length/truncation flag) and candidate IDs/section titles/
scores/threshold eligibility before filtering (at most 500 candidates). Rule bodies, raw provider
responses and keys are not included. Query text may contain private user data, so documentation
instructs local use, review before sharing and turning it OFF afterward. This adds no provider
calls and makes no ranking, prompt or threshold changes. It diagnoses the unresolved failure;
it is not another claimed fix. Tests cover off-by-default behavior, rejected-candidate visibility,
no rule-body logging, JSON escaping and Unicode/candidate bounds. `check bootJar` passed with
299 unit tests and 107 PostgreSQL/pgvector integration tests (406 total, zero failures/errors/skips),
using mock providers and disposable databases. `git diff --check` passed.
No paid calls, private environment/database changes, commit or push.

Further manual-test diagnostics, request `f24ef4b2-86e1-44b2-bff3-cf93a49d371c`, show the
applied rewrite asked about the risk of keeping all four starting Destination Tickets with game
name/setup framing. It did not expand risk into consequences/penalties. Only the introduction
passed the 0.5 retrieval threshold (~0.583). Game End ranked ninth at ~0.443 and was rejected;
the answer fallback was again `MODEL_INSUFFICIENT_EVIDENCE`. The stored passage and indexing
exist, but this query's ranking/threshold combination fails to supply the necessary evidence.
Increasing top-K alone would not bypass the threshold; lowering only retrieval threshold would
also leave the independent 0.5 answer gate in effect. These are observed diagnostic results,
not evidence that the threshold should be globally lowered. No additional implementation or
paid calls were made for this log analysis. Live retrieval quality remains unresolved.

### Consequence-search rewrite instructions — 2026-10-09

Following the diagnostic failure above, the generic rewriter prompt now explicitly expands
risk/downside into possible consequences or penalties rather than repeating the vague word.
It requests short content-focused queries without unnecessary game/edition framing, since search
already filters by version. It distinguishes the current action's stage from when consequences
could occur, and forbids inventing a penalty, amount, completion condition or outcome. A fictional
mission-card example illustrates wording only; no Ticket to Ride rule or answer was hardcoded.

This change affects rewrite instructions only. Thresholds, source/citation validation, call counts,
provider settings and deterministic fallback are unchanged. Two mock-provider cases check prompt
delivery and returned search cues for risk/downside; they do not establish live model compliance
or improved ranking. README and architecture notes describe the refinement and pending retest.

Validation: `check bootJar` passed with 301 unit tests and 107 PostgreSQL/pgvector integration
tests (408 total, zero failures/errors/skips), using mock providers and disposable databases.
`git diff --check` passed. Live retrieval of the missing Game End passage and broader evaluation
remain unresolved; earlier live successes and failures are preserved. No paid API calls, private
environment/database changes, commit or push.

### Offline threshold and evidence-gate evaluation — 2026-10-09

Manual retest request `39f2a28f-bff0-4ffc-b163-2323b588273d` applied the refined rewrite:
“What consequences or penalties can result from keeping all four starting destination tickets?”
Game End improved to rank two, but its similarity was 0.43110639678163876, below 0.5.
All 16 candidates were rejected and the fallback was `NO_RETRIEVED_EVIDENCE`; no answer-model
call occurred for this request. The screenshot still shows LOW. The prompt refinement therefore
did not resolve the live failure, even though it improved the relevant passage's rank.

Added `ThresholdEvaluationTest`, an offline comparison through real retrieval/prompt/answer services
with provider/store doubles. Seven fictional question cases (quantity bounds, returned cards, risk,
unsupported phone number) exercise four gate pairs: 0.5/0.5, 0.4/0.5, 0.5/0.4, 0.4/0.4. Only
the risk score above is replayed from a live log; other scores, vectors, source text and answers
are synthetic. The 28-case report is `build/reports/threshold-evaluation.json`. It is not a
semantic-quality benchmark, a measured improvement or a threshold calibration dataset.

Results: either 0.5 gate blocks the 0.431 risk fixture. Lowering both gates admits it and accepts
the double's cited answer. Strong fixture cases remain accepted; the unsupported case abstains
only because its provider double reports insufficient evidence. A separate characterization
test confirms the current gap: an invented phone number with an exact but irrelevant rule quote
can be accepted even at baseline thresholds. Provenance checks do not verify semantic entailment.
No automatic relevance verifier was implemented and no production thresholds were changed.
Lowering both gates is not recommended on this evidence; labeled live cases and relevance/answer
support evaluation remain necessary. Existing live walkthrough history and limitations are preserved.

Validation: the focused evaluation passed all 29 tests. `check bootJar` passed with 330 unit
tests and 107 PostgreSQL/pgvector integration tests (437 total, zero failures/errors/skips).
`git diff --check` passed. The initial sandboxed Gradle run could not connect to its local daemon;
rerunning outside the sandbox succeeded. No paid API calls, private environment/database changes,
commit or push.

### Optional relevance selection and cited-answer support review — 2026-10-09

Added an opt-in experimental answer path, `RAG_EVIDENCE_REVIEW_ENABLED=false` by default.
It uses a separate candidate floor/count (0.35/12 defaults; finite 0–1 and 1–20 bounds) after
version/profile/metadata checks, deduplication and bounded ranking. This explicitly replaces the
baseline similarity gates for reviewed answers only; ordinary search/default answers are unchanged.
Explicit API top-K/threshold overrides can restrict this pool. Original scores are never inflated.

Provider-neutral `EvidenceReviewClient` has two strict contracts through the shared Responses
adapter: relevant supplied chunk IDs, and a boolean cited-answer support decision. Mini and Luna
retain their existing compatible request parameters. Selection runs over budgeted original
question/context/excerpts before generation. It cannot add source text or unknown/duplicate IDs.
After existing ID/version/exact-quote checks and optional quote correction, verification receives
the original question, budgeted context, proposed answer and validated cited quotes only, with
uncited excerpts removed. Context is not rule evidence. It checks relevance, quantities, stage,
contradictions and support for every claim. Rejected/refused/invalid/error reviews return LOW;
successful reviewed answers are capped at MEDIUM. Workflow, UI, database and milestone are unchanged.

Reviews add at most two LLM calls using existing model/effort/output/timeout settings. Alongside
optional rewriting, generation and quote correction, a question can use five LLM calls plus an
embedding; speech is separate. No review retries or overall deadline were added. Review input
caps are 32000/40000 characters. Logs contain outcomes/counts/timing and safe error codes only.
README, architecture, application configuration and `.env.example` document opt-in, rollback and
cost/latency. The private `.env` was not read or changed and the live database was not modified.

Limitations: this is model-assisted relevance/support checking, not a correctness proof. The same
model may make correlated mistakes; bounded candidates and prompt budgets may omit essential
evidence. The .35 floor is experimental, not calibrated. Live resolution of the Ticket to Ride
risk case remains pending; all earlier manual-test successes and failures and evaluation limitations remain.
No commercial rulebook text is included in fixtures. No paid API calls, commit or push.

Validation: `check bootJar` passed with 369 unit tests and 107 PostgreSQL/pgvector integration
tests (476 total, zero failures/errors/skips); `git diff --check` passed. Mock-provider cases
cover lower-scoring passage selection, preserved scores/provenance, selection failures, support
rejection/error, exact-but-irrelevant quotes, unknown/unselected citations, quote correction,
empty indexes, disabled behavior, foreign versions and input/configuration bounds. Local HTTP
contracts cover Mini/Luna parameters, strict schemas, malformed IDs/booleans, refusals and duplicate/
oversized selections. These test orchestration/contracts, not real semantic accuracy. Enabled
review is covered through real services with doubles; existing HTTP/database integration checks
exercise the disabled default. The first focused run found one invalid test fixture with blank
LLM instructions; that fixture was corrected before the passing full run.

### OpenAI-configured startup wiring correction — 2026-10-09

The first manual review-enabled startup failed because the OpenAI `llmClient` implements
`EvidenceReviewClient` at runtime alongside the explicit `evidenceReviewClient` wrapper. Spring
found two candidates. The prior default/doubled startup checks did not exercise this real adapter
with both application consumers. This was a wiring defect, not an API-key, model or compiler issue;
the build already enables `-parameters`.

Marked the explicit evidence-review and query-rewrite wrapper beans `@Primary` so both ports
resolve deterministically when the shared OpenAI adapter also implements them. No provider
parameters, evidence policy, workflow or private settings changed. Added four no-call Spring
context regressions using a dummy key: Mini/Luna with both features enabled/disabled. All four
reproduced the failure before the fix. They exercise actual service constructor injection and
assert that each consumer receives its preferred port bean, without generating/reviewing anything.

Validation after correction: `check bootJar` passed with 373 unit tests and 107 PostgreSQL/pgvector
integration tests (480 total, zero failures/errors/skips), including all four new configured-adapter
startup cases. `git diff --check` passed. Live answer/review quality and all previously recorded
limitations remain unverified. No paid API calls, private environment/database changes, commit or push.

### Manual reviewed-answer walkthrough and citation-detail refinement — 2026-10-09

After the wiring fix, browser screenshots from manual testing show the initial all-four ticket answer
followed by a successful risk response explaining lost points for unfinished retained tickets.
Expanded sources show an Object of the Game warning about point loss and the setup retention
rule. Game End is not required if another supplied passage supports the actual claim. This
supersedes the pending risk-answer retest for this individual exchange only; earlier failures remain
recorded. No request logs or active model/effort settings accompanied these screenshots, so they
are limited manual-test observations, not an independently instrumented benchmark of review decisions.

Seven additional screenshot cases show expected answer behavior: initial five rejected; initial
one rejected; unwanted starting tickets returned to the shuffled deck bottom; previously retained
tickets cannot be discarded; an unfinished-ticket follow-up resolves its pronoun and explains
point loss; an instruction to ignore the rules does not override the initial four-card deal; and
the unsupported customer-service-number question returns LOW. Rule answers display MEDIUM and
expanded relevant setup, drawing-ticket or objective sources. Persisted workflow state, latency,
cost, repetition stability and broader source coverage were not independently verified.

Citation gap observed before the refinement: the unfinished-ticket answer specifies deduction of the ticket's value
from the final score, but its displayed quote establishes only point loss. Previously supplied
Game End content supports the precise deduction; that precise evidence is not displayed in this
answer's citation. Correct rule knowledge does not establish complete quote-level support.
This demonstrates a live limitation of the model-assisted support check despite successful
answer behavior. Other details (such as initial deal size) likewise require actual quoted premises.

The generic selection prompt now requests precise applicable amount/calculation/timing/condition
rules when available. Generation and quote-correction instructions require the quoted passages
themselves to support each detail, otherwise select a more precise quote or reduce answer
specificity. The support-review prompt audits those details individually and rejects missing
quoted premises. No game-specific checks, extra calls, new retry, schema changes, threshold
changes or workflow modifications were added. Review remains optional and experimental.

Three original fictional-rule regression cases cover a general answer/general quote, a precise
answer/general quote rejected by the review double, and a precise answer/precise quote accepted.
They verify delivered instructions, quote-only reviewer input and fallback/provenance behavior;
they do not prove real-model enforcement. The citation-detail refinement awaits live retesting;
broader evaluation and correlated model-error limitations remain. No commercial rulebook text
was added. README/architecture notes reflect the narrow live evidence and remaining limitation.

Validation: `check bootJar` passed with 376 unit tests and 107 PostgreSQL/pgvector integration
tests (483 total, zero failures/errors/skips); `git diff --check` passed. Live verification of
the refined citation behavior remains pending. No paid API calls, private environment/database
changes, commit or push.

### Successful citation-detail retest — 2026-10-09

Subsequent screenshots from manual testing with sources expanded show both requested retests succeeding.
The previously retained-ticket discard answer cites the Draw Tickets restriction on discarding
only newly drawn tickets. The unfinished-ticket follow-up resolves its pronoun and now cites
Game End's specific value-deduction rule for its claim about the final score. Both display MEDIUM.
The previously observed citation-detail gap is resolved for this individual exchange, superseding
the pending live retest above. Earlier incomplete citations and failures remain historical evidence.

This is a limited manual-test observation. Active model/effort, actual review decisions, request IDs,
workflow state, latency/cost and repeated-run stability were not independently verified from these
screenshots. Broad semantic accuracy, citation completeness, calibrated confidence and same-model
review reliability remain unverified. The review path remains optional and experimental.

Documentation-only update: no source/configuration changes or new runtime tests. The last recorded
automated validation remains 483 passing tests; `git diff --check` passed for this update.
No additional paid API calls, private environment/database changes, commit or push.

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

Status: Complete and validated with mock providers; a limited manual OpenAI demo passed on 2026-10-08. Broader live quality evaluation is still pending.

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

Status: Complete and validated with deterministic mock providers. Live-model quality remains unverified.

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

Status: Complete and validated with mock providers; the manual live teaching/session walkthrough passed on 2026-10-08. Setup integration remains Milestone 7.

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

Status: Complete and validated with mock providers; setup-specific live provider/physical walkthroughs remain unverified.

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

Status: Implemented and validated with mock providers and a synthetic-microphone browser walkthrough. Live speech quality remains unverified.

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
The private `.env` and existing Compose database were not read or changed.

- Optional independently configured STT/TTS interfaces and OpenAI HTTP adapters default to disabled. Multipart/JSON provider contracts, bounded responses, timeouts, safe failures and empty transcription are covered by fixture tests.
- The multipart session voice endpoint validates type/signature/size, preserves recognized text if answering fails and invokes the existing version-pinned question pipeline. Revision guards reject navigation races. TTS failure preserves the stored text/citations and returns a structured audio error; raw and generated audio are not persisted.
- A thin Thymeleaf browser demo loads catalog games, defaults to STANDARD, supports player count, progressive lesson/setup controls, same-session PLAYING, text questions, citations, bounded history, read-only review and completion. No frontend workflow or RAG engine was added.
- Push-to-talk requests the microphone on user action, stops tracks on release/cancellation and provides a file-upload alternative. The UI displays transcription, playable MP3 metadata/controls and understandable errors, with independently usable text. Spoken answers are disclosed as AI-generated.
- User-requested spoken teaching/setup guidance also reuses the TTS adapter. Start-screen/session controls enable narration of the first and subsequent lessons/steps, with pause/stop/replay. The backend selects authored pinned content and segments long explanations without changing progress/history; frontend preferences suppress duplicate automatic narration after refresh/questions/review. STT and RAG are not required for authored narration.
- README, architecture, OpenAPI description and `.env.example` now describe implemented Milestone 8 behavior and manual demonstrations. Private keys remain server-side. No new database migration or changes to V1–V7 were needed.
- Automated checks and browser evidence are recorded under Milestone 8 validation below. The original manual OpenAI walkthrough is retained, including its remaining evaluation limitations. No future work was implemented.

### Follow-up: natural clarification retrieval

- Manual Ticket to Ride USA testing found that "Is that okay if I just keep 2 tickets?" returned LOW, while an explicit initial-setup question returned a supported MEDIUM answer. This suggests a contextual retrieval limitation; the precise original fallback branch was not captured.
- Retrieval now includes bounded active setup instructions and lesson explanations alongside titles and the latest user question. The original question remains intact; Unicode-safe per-field and overall budgets apply. No extra model call, threshold reduction or change to evidence/citation validation was introduced.
- Regression tests cover natural wording, teaching-only context, long/Unicode cues and a mock-provider pgvector session journey with preserved setup progress and citations. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed: 220 unit tests and 105 isolated integration tests (325 total; zero failures, errors or skips), plus executable JAR assembly. `git diff --check` passed. No paid API calls, commit or push were performed.
- Live OpenAI retesting of this change remains pending. Deterministic fixtures establish context delivery and workflow behavior, not real embedding relevance or improved answer accuracy. Previously recorded live results and broader evaluation limitations remain unchanged.

### Follow-up: answer rejection diagnostics

- The next manual Ticket to Ride test still returned LOW at "Choose your starting tickets". Shared logs show 16 candidates, 13 below threshold, three returned matches and an LLM call; they do not identify the selected passages or precise fallback branch. The natural-follow-up issue remains unresolved, and no model-quality improvement is claimed.
- Added INFO diagnostics identifying retrieved chunk/document IDs, bounded single-line section titles, pages, similarity, prompt inclusion and the minimum answer evidence score. Each fallback now identifies its rejection reason and offending citation ID when applicable. No questions, rule bodies, generated answers/quotes, credentials or audio are logged by the new diagnostics. No models, thresholds, response contracts or evidence gates changed.
- Tests exercise rejection reasons, prompt-budget exclusion and metadata-only logging, including sanitized/truncated titles. On 2026-10-08, `gradlew.bat test bootJar --no-daemon --console=plain` passed all 222 unit tests (zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. The 105 integration tests passed in the preceding context-improvement check and were not rerun for this diagnostics-only change. No paid API calls, commit or push were performed; historical live walkthrough results and remaining evaluation limitations are preserved. The next manual test needs to capture the new logs to determine the live failure cause.

### Follow-up: bounded quotation correction

- Live diagnostics from manual testing on 2026-10-08 identified `QUOTE_MISMATCH` for the Draw Tickets chunk. The Setting up the Game passage was also retrieved (similarity 0.563942, included in prompt). This confirms the observed rejection was citation validation, not absence of the setup passage. The generated quote was not logged, so its precise textual error remains unknown.
- Strengthened citation instructions: copy contiguous exact quotes from the matching excerpt, and distinguish active setup from later turns using session context. An otherwise valid answer with a quote mismatch now permits one correction call using unchanged serialized evidence/context and feedback containing the rejected chunk ID, never the rejected answer/quote. Full validation applies again; no third call, relaxed thresholds or automatic quote substitution.
- Added mock-provider tests for successful correction, repeated mismatch and other rejected corrections, no retry for non-quote failures, provider-error propagation, unchanged evidence budget, and a PostgreSQL session test for a single final persisted exchange with unchanged setup progress and no partial history on correction failure. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 230 unit tests and 106 isolated integration tests (336 total, zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. Live accuracy of this correction remains unverified; broader evaluation and previously recorded walkthrough results are preserved.
- No paid API calls, commit, push, private environment access or local-database modifications were performed. Live correction can add one paid LLM request and its configured per-call timeout; retrieval/indexing are not repeated.

### Follow-up: controlled local quotation comparison

- Logs from manual testing from 2026-10-08 show three separate requests: at 11:03 UTC a quote correction was accepted; at 11:04:12 a model-insufficient-evidence fallback occurred; at 11:04:49 a quote mismatch persisted through the second attempt. Setup evidence was included in all three. Questions were not logged, so exact mapping to the five-ticket question is unknown. One accepted correction does not establish general answer quality.
- Added a dedicated quotation-comparison logger, OFF by default and enabled locally with `ANSWER_QUOTE_DIAGNOSTICS_LEVEL=DEBUG`. It captures the rejected quote and cited passage as escaped JSON, attempt/source IDs, original content length/truncation and other prompt chunk IDs containing the exact quote. Passage output is bounded at 8000 UTF-16 characters with Unicode-safe truncation. Existing INFO logs omit content. No question/full answer or credentials are added to these diagnostics.
- README and `.env.example` explain temporary opt-in, restart, single reproduction and switching OFF again; captures contain rule text and should remain outside Git. The private environment was not accessed or changed. Tests use fictional text and cover disabled logging, exact escaped text, wrong-source identification and bounded Unicode output. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 233 unit tests and 106 isolated integration tests (339 total; zero failures/errors/skips) and built the executable JAR; `git diff --check` passed.
- Citation checks, prompt behavior, retry bounds, models and thresholds are unchanged. No paid API calls, commit, push or local-database modifications were performed. Precise mismatch text and supported-deduction quality still require manual capture/evaluation; previous walkthrough records and broader limitations are preserved.

### Follow-up: whitespace-tolerant source quote matching

- Opt-in diagnostics from manual testing for request `68440d48-811b-4691-bd82-8be945bd5ef3` established that attempt 1 inserted ellipses between omitted sentences, while attempt 2 preserved the cited Draw Tickets wording but flattened paragraph breaks to spaces. The latter was rejected solely by the strict substring check. These findings do not verify the unseen generated answer's setup-versus-gameplay interpretation.
- Citation validation now tolerates whitespace-run differences only within the cited prompt chunk and returns the original contiguous source span. Non-whitespace text remains literal; ellipses, omitted/changed words, changed numbers/punctuation/case/order and wrong source IDs still fail. Generated and restored quotes remain bounded at 1000 UTF-16 characters. Whitespace-only matches avoid an extra correction call. The HIGH-confidence minimum-length gate uses restored source text so model-added spaces cannot inflate it. Models, thresholds, retry cap and workflow ownership are unchanged.
- Regression tests use fictional rules to cover whitespace/Unicode, source restoration, omitted/altered text, literal regex characters, size limits and protection against model-added whitespace inflating HIGH confidence, plus session persistence of original source quotes without setup movement or a retry. On 2026-10-08, `gradlew.bat check bootJar --no-daemon --console=plain` passed 247 unit tests and 107 isolated integration tests (354 total; zero failures/errors/skips) and built the executable JAR; `git diff --check` passed. No paid API calls, commit, push, private environment access or local-database changes were performed. Live retesting, supported-deduction quality and broader evaluation remain pending; previous results are preserved.

### Earlier implementation record (Milestone 7)

Milestone 7 was implemented on `milestone-7-guided-setup`; the following records its original scope and validation.

- V7 adds immutable version-bound SetupFlow/SetupStep and persisted session setup progress, flow/step/return-lesson ownership constraints and setup-trigger configuration. V1–V6 migrations are unchanged.
- New sessions pin the configured setup flow. Legacy sessions can pin that version's latest setup at first explicit start; later publications/default changes never repin started setup.
- CONTINUE at an unfinished setup-trigger lesson enters SETUP and saves that same lesson without completing it. Explicit start, current, next, previous, repeat, restart and complete APIs are implemented. Completion acknowledges the last step only after prior steps and returns to TEACHING, never directly PLAYING; another Continue advances normally.
- Restart clears setup confirmations while retaining teaching progress/history. Step navigation uses locked revision checks. Questions preserve setup progress; context includes the active step as untrusted context rather than citation evidence. Provider failures and stale answers leave no partial exchange.
- Six original Catan beginner setup steps reference the physical beginner diagram. A replacement seed teaching flow has QUICK 8 / STANDARD 10 / DETAILED 11 sections. Existing sessions keep the V6 flow; user-published Catan teaching is not replaced. No commercial rulebook/diagram is bundled or automatically indexed.
- README includes provider-free Catan and Lantern Trail setup demonstrations, revision handling and optional setup Q&A. No new environment variables, dependency/provider changes or private `.env` access were needed.
- Full validation passed 279 tests (192 unit, 87 isolated PostgreSQL/HTTP integration), zero failures/errors/skips, and built the executable JAR. Existing live OpenAI evidence is retained below; no new paid calls or local-database operations were performed.

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

Spoken-guidance follow-up on 2026-10-08: implemented as a requested follow-up within Milestone 8.
The original 316-test implementation record below is retained.

- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS: 322 tests (218 unit, 104 isolated PostgreSQL/HTTP integration), zero failures/errors/skips; executable JAR built. Both browser scripts passed JavaScript syntax checks.
- New coverage verifies pinned authored lesson narration, shared Explain again text, setup instructions/notes/warnings, long-segment bounds and Unicode preservation, one synthesis call per requested segment, no database transaction during TTS, unchanged session/history, safe provider failures, invalid/stale requests and discarded audio after concurrent navigation.
- Headless Edge/Playwright with a disposable database and fake providers passed first-lesson narration after start-screen opt-in, decoded synthetic silent MP3 playback, Stop/Replay and current-page audio reuse, unchanged session state, refresh/review suppression, Explain again, setup/Repeat narration, opt-out and mobile layout. No JavaScript page errors occurred. The browser fixture substitutes a locally constructed silent MP3 for fake provider audio; this verifies playback mechanics, not real TTS quality or human-like speech. The mobile screenshot was visually inspected.
- No paid API calls, private `.env` reads, local database changes, staging, commits, pushes or branch changes. The temporary preview process/database were removed after validation. Prior live OpenAI observations are retained; live narration/STT quality and the broader evaluation limitations remain unverified.

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- `gradlew.bat check bootJar --no-daemon --console=plain` PASS: all 316 tests (217 unit, 99 isolated PostgreSQL/HTTP integration), zero failures/errors/skips; executable JAR built. Provider requests use local HTTP fixtures or doubles; Testcontainers databases are separate from the local Compose database.
- Coverage includes audio MIME/signature/size checks, binary defensive copies, multipart STT and JSON/MP3 TTS contracts, disabled providers, redacted configuration/errors, response bounds, malformed/empty responses, timeouts/rate limits, existing cited session answers, TTS partial failure, preserved transcription on LLM failure, unchanged progress/history on rejected requests, completed/missing sessions and setup changes during transcription. Tests verify that speech calls do not hold database transactions.
- Real HTTP/Thymeleaf tests cover backend-loaded games, STANDARD selection, provider-free teaching, generic teaching/setup/resume/playing/completion, read-only review, citations/history, escaping, stale form submissions, safe errors and page headers. Initial focused checks exposed a reserved Thymeleaf variable, test-fixture mistakes and URL session rewriting; these were corrected before the passing full suite.
- Headless Edge via Playwright passed a desktop/mobile Lantern Trail journey with a synthetic microphone, fake STT/TTS/embedding/LLM providers and an isolated PostgreSQL container. Verified push-to-talk upload, displayed transcription, saved cited answer/history, audio Blob controls, unchanged active setup step, resumed teaching, PLAYING, non-mutating review and retained history after completion. No JavaScript page errors occurred. Desktop/mobile screenshots were visually inspected; browser checks required execution outside the sandbox.
- Limitations: the synthesized MP3 in this browser fixture is header-only test data, so the check verifies audio transport/controls, not successful decoded playback or intelligible speech. Human microphone permission/device behavior, real STT accuracy and live TTS quality were not tested. No paid calls were made. Full-codec validation, server duration enforcement, production authentication and retention policies remain outside this local demo. The prior live OpenAI walkthrough and broader evaluation limitations below remain unchanged.
- No staging, commit, push or branch change. V1–V7, existing private configuration and local database were preserved. The temporary preview process and its disposable Testcontainers database were removed after validation; ignored check scripts/logs/screenshots remain under `build/`.

### Milestone 7

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Compilation and focused domain/session integration checks passed before the full suite.
- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS: all 279 tests (192 unit, 87 isolated PostgreSQL/pgvector integration), zero failures/errors/skips; executable JAR built. AI calls use doubles, without real keys or paid calls. Testcontainers databases are separate from the local Compose database.
- Coverage includes generic and seeded Catan journeys; all depths; explicit/triggered setup; current/next/previous/repeat/restart/complete; first/last/single-step boundaries; saved teaching resume; readiness after further teaching; history and progress preservation during Q&A; evidence/context isolation and prompt budgets; missing/invalid configuration; immutable flow/version pins; provider failure; duplicate-action and restart-versus-question races; transaction rollback; database ownership; V6 upgrade/session/history/custom-publication preservation and repeatable migrations.
- Existing Milestone 0–6 checks, including deterministic retrieval evaluation and earlier migration/data-preservation tests, pass. Original live OpenAI walkthrough text is unchanged.
- Limitations: no live setup-specific OpenAI or physical-board walkthrough was performed. Catan guidance is an original concise beginner configuration requiring the physical rulebook diagram, not a complete rulebook or automatic board verification. Authored setup is not automatically indexed as evidence. Broader live grounding, confidence calibration, multi-game/adversarial/provider-failure evaluation remain deferred as recorded below. Legacy flows without setup triggers keep their earlier readiness behavior.
- No staging, commit, push or branch change. Milestone 8 remains unstarted: no speech adapters, audio endpoints, templates or browser UI.

### Manual OpenAI walkthrough — 2026-10-08

Evidence: Swagger response bodies and the final history response from manual testing.
These observations are separate from automated provider tests and do not constitute a benchmark.
The reported configuration used OpenAI embeddings
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
- This supersedes blanket "all live testing deferred" statements for these exercised paths only. Broader Catan/multi-game live evaluation, long or ambiguous conversation quality, adversarial prompts, provider failure/rate-limit behavior, threshold calibration and factuality/hallucination measurement remain unverified. Previous automated/mock-provider results and historical deferral statements remain unchanged below.
- Documentation-only recording: no source/configuration edits, paid calls, database operations, staging, commit, push or branch change performed for this documentation update. Application tests were not rerun for this documentation-only update; the 254-test result below belongs to Milestone 6 implementation validation. Documentation whitespace check passed.

### Milestone 6

Validation on 2026-10-08 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- Initial Java compilation passed. The first full check passed all 175 unit tests and 72/73 integration tests; its only failure was a historical Milestone 4 OpenAPI assertion that session endpoints must be absent. That assertion was updated for Milestone 6 while preserving the stateless question contract. Additional context-budget, Unicode, simultaneous-question and database rollback checks were then added. The subsequent full `check bootJar` passed all 254 tests. Final user-facing wording was also revalidated with the same full command.
- `gradlew.bat check bootJar --no-daemon --console=plain` — PASS. All 254 tests passed: 179 unit and 75 isolated PostgreSQL/pgvector integration tests, zero failures/errors/skips. Executable JAR rebuilt successfully. Existing Milestone 0–5 checks remain passing, including deterministic retrieval evaluation.
- Unit coverage includes ordered immutable depth-specific content, section validation, persisted-state transitions, completion, first/last navigation boundaries, nonadvancing controls, wrong-flow rejection, bounded context defaults/limits, whole-message selection, contextual retrieval bounds/Unicode and structured untrusted context that cannot become evidence. Prompt budgets remove older context before sacrificing retrieved evidence.
- Seventeen new PostgreSQL/real-HTTP integration cases cover session creation/default depth/player validation, seeded Catan depth selection, generic flow publication, immutable flow/default pinning, progressive navigation and explain-again examples, saved citations and contextual follow-ups, question-preserved lessons/resumption, cross-session isolation and history cursors, empty/weak evidence fallback, safe provider timeout with no partial exchange, same-session PLAYING/COMPLETED transitions, concurrent navigation/questions, stale answers and atomic rollback after assistant-message insertion failure.
- Migration checks cover fresh V6 startup, repeatability, flow/version/section/lifecycle constraints and V5-to-V6 upgrade preserving catalog rows, imported text, chunks and existing vectors. Earlier V1/V3/V4-to-current migration checks are retained and updated for the added migration.
- Tests use local mock providers and isolated Testcontainers databases; no paid calls or real API keys. Existing Compose data/services and private `.env` were not read, changed or migrated. Restart the local app to apply V6 for manual use. Provider-free teaching and optional live Q&A instructions are in README.
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
- Tests use local mock providers and isolated Testcontainers databases. No real API keys, paid calls, existing Compose data, `.env` edits or local service changes are required. No Milestone 6 work, commit, push, staging or branch change is performed.
- `git diff --check` and source/fixture whitespace checks — PASS. AGENTS.md, PROJECT_SPEC.md, dependencies and migrations are unchanged; Flyway remains at V5. Changes remain unstaged and uncommitted on `milestone-5-production-quality-rag`. README and architecture documentation describe defaults, API examples, evaluation reproduction and limitations.

### Milestone 4

Validation on 2026-10-07 using installed Temurin JDK 21.0.12.1 and Gradle 8.14.4:

- The first unit run exposed a fixture using an unsaved GameVersion without an ID; the fixture was corrected. A subsequent full run passed all 137 unit tests and 42/51 integration tests; nine new integration cases failed while replacing an existing Mockito answer with `when`, which invoked the prior answer with a null matcher argument. Mock providers were corrected to use `doAnswer`/`doReturn`/`doThrow`. The final passing full run below supersedes these intermediate fixture failures.
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
- Existing bootstrap health/Swagger/vector/error tests also passed. No paid AI calls were made, and isolated Testcontainers databases were used rather than the local Compose database.
- Manual demonstration commands are in README. An already-running local application must be restarted to load the new code and apply V2/V3 to its retained database volume; that local database was not changed by these checks.

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

- No remaining automated Milestone 8 acceptance blockers are known. Docker/WSL is working. The limited manual OpenAI walkthrough above passed; broader live provider/quality evaluation and live STT/TTS quality remain deferred. Browser voice validation used a synthetic microphone and fixture audio rather than human speech or live synthesis.
- Session/teaching/setup APIs are local-development functionality without authentication. Flows are append-only and existing sessions pin their original content. Reference Catan lessons are an original introductory overview, not a complete commercial rulebook; authored reading pointers are not verified generated-answer citations. Setup confirmations are recorded, but the application cannot verify the physical board. Setup-specific live provider/physical walkthroughs remain unverified.
- Session context keeps a bounded window of whole messages; an oversized newest message can leave no retained history. Retrieval uses deterministic recent-user-topic/lesson/setup-title cues rather than model-driven rewriting. The prompt budget may further remove context to retain source evidence. Summarization, persisted expansion selection, retention/deletion policies and live follow-up quality remain unimplemented/unverified.
- Session revisions reject duplicate actions and discard stale generated answers after concurrent changes with no partial exchange. Provider requests completed before a conflict may still incur charges; no automatic retry is performed.
- Docker access and localhost checks required execution outside the sandbox. Future integration checks need access to the running Docker Linux engine.
- Catalog, rule import, indexing, retrieval and question answering are local-development functionality without authentication. PDF extraction, edit/delete APIs, full BCP 47 registry validation and later-milestone functionality remain outside this implementation. Repeat imports create additional documents; retrieval suppresses duplicates without deleting stored documents/chunks.
- Automated tests establish provider contracts and deterministic database behavior without real API keys. The manual live demo additionally exercised narrow retrieval/generation and session paths, but is not a real-model relevance benchmark. Provider token limits can still reject text because local bounds count characters, not tokens.
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

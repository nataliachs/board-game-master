# Board Game Master — Project Specification

This document defines the product requirements and architecture. It describes the intended
behavior; [development status](docs/DEVELOPMENT_STATUS.md) records what is implemented and
what has actually been tested.

## 1. Product goal and customer journey

### Core product experience

Board Game Master teaches a selected board game, guides physical setup, and answers rule
questions during play. It should help someone who has never played the game get started
without having to work out which questions to ask first.

The platform supports multiple games. Catan is the first reference implementation, but shared
services must not depend on Catan-specific rules.

### Default customer journey

Game selection → Player count → Teaching depth → Create session → Game introduction →
Objective → Components → Guided physical setup → Core rules → Turn structure → Common
situations → Ready to play → In-game questions → Complete session.

Players can ask clarification questions at any point. The application retrieves supporting
rules, answers the question, preserves lesson/setup progress and lets the player continue
where they left off. For example, a question about whether cities receive the same resources
as settlements should interrupt the explanation without advancing or resetting the lesson.

### Game selection and version resolution

Players normally choose the logical Game, without selecting an edition, document version or
language-specific ruleset. The backend resolves an active default GameVersion and stores it
on the session so the ruleset stays stable. For the reference configuration, that is
Catan → Catan / 5th Edition / English.

An edition/version selector can be added later if needed; it is not required for the initial
customer flow.

### Teaching depth

QUICK, STANDARD and DETAILED are explanation depths within the same session, rather than
separate QUICK_START, LEARN or ASK_RULES modes. Rule questions are available at every depth.

#### QUICK

Teach the essentials needed to start playing. Introduce situational rules when they come up,
keeping the initial explanation short.

#### STANDARD

This is the default café experience. Cover the theme, objective, components, setup, core
mechanics, normal turn structure, common situations and winning condition.

#### DETAILED

This depth may include extra examples, less common rules, strategic context and edge cases before play begins.

### Session stages

The backend persists TEACHING, SETUP, PLAYING and COMPLETED stages and the current teaching
or setup position. CANCELLED and PAUSED are possible future additions. Session created → TEACHING → SETUP → TEACHING → PLAYING → COMPLETED.

Setup can interrupt teaching. Completing setup returns to the saved lesson before teaching
continues. The LLM does not decide the current stage or remember workflow progress.

## 2. Engineering priorities

Prioritize, in order:

1. Correctness
2. Maintainability
3. Testability
4. Clear architecture
5. Reliability
6. Developer experience
7. Performance
8. Additional features

The project should demonstrate maintainable application code, tested workflows and clear failure handling. Avoid unnecessary abstractions, microservices, distributed systems, Kubernetes, event buses, and premature optimization. Use a **modular monolith**.

---

## 3. Required tech stack

Backend:

- Java 21+
- Spring Boot
- Spring Web
- Spring Data JPA
- Spring Validation
- PostgreSQL
- pgvector
- Flyway
- Docker Compose
- Gradle unless the existing repository already uses Maven

Testing:

- JUnit 5
- Mockito where appropriate
- Testcontainers for PostgreSQL integration tests
- Spring Boot integration tests

API documentation:

- OpenAPI / Swagger

AI:

- LLM provider behind an abstraction
- Embedding provider behind an abstraction
- Do not tightly couple the domain code to one AI vendor

Voice:

- Speech-to-Text provider abstraction
- Text-to-Speech provider abstraction

Demo UI (Phase 5 / Milestone 8):

- Spring Boot + Thymeleaf, HTML, CSS and minimal JavaScript
- No separate frontend project or React/Vue/Angular unless technically justified
- Thin UI reusing backend application services

The platform must remain game-independent at the infrastructure level.

RAG, sessions, conversations, teaching, setup, voice, document ingestion, and retrieval must work generically for any supported game.

---

Docker Compose runs the local PostgreSQL/pgvector service. Add Redis only when there is a demonstrated need.

---

## 4. Architecture

Use a modular monolith organized primarily by domain/feature rather than by technical layer.

Prefer something conceptually similar to:

src/main/java/.../

game/
    domain/
    application/
    infrastructure/
    api/

rules/
    domain/
    application/
    infrastructure/
    api/

rag/
    application/
    infrastructure/

session/
    domain/
    application/
    infrastructure/
    api/

gamemaster/
    application/

setup/
    domain/
    application/
    api/

ai/
    llm/
    embedding/
    speech/

common/
    exception/
    config/
    validation/

Do not force every module to contain every subfolder if it does not need them. Keep domain logic independent from external AI SDKs. Use interfaces at integration boundaries.

Examples:

LlmClient
EmbeddingClient
SpeechToTextClient
TextToSpeechClient

Provider-specific implementations should live in infrastructure code.

---

## 5. Domain model

Design the domain so multiple board games can be supported without changing core application architecture.

#### Game

A Game represents a playable board game known to the platform.

Possible fields:

- id
- slug
- name
- publisher
- minPlayers
- maxPlayers
- estimatedDurationMinutes
- description
- active

Do not place edition directly on the Game if doing so prevents one game from having multiple versions. Prefer separating the logical game from its rule version.

For example:

Game

- Catan
- Azul
- Splendor

and:

GameVersion

- Catan / 5th Edition / English
- Catan / 6th Edition / English
- Catan / 5th Edition / Indonesian

#### GameVersion

Represent the specific ruleset used by a physical/versioned copy of a game.

Possible fields:

- id
- gameId
- edition
- versionLabel
- language
- releaseYear
- active
- defaultVersion

A Game may have one or more GameVersions.

For the initial MVP:

Catan should have one configured default GameVersion.

The customer therefore selects only:

Catan

and the backend resolves the default supported version automatically. Later, a café configuration could associate a physical copy with a specific GameVersion.

For example:

Catan Copy #1
→ Catan 5th Edition / English

Catan Copy #2
→ Catan 5th Edition / Indonesian

Do not require this café inventory functionality during Phases 1–5.

---

#### RuleDocument

Represents an imported source document associated with a specific game ruleset.

Possible fields:

- id
- gameVersionId
- name
- sourceType
- version
- language
- sourceReference
- importedAt

Examples of sourceType:

- RULEBOOK
- FAQ
- ERRATA
- EXPANSION

A RuleDocument must belong to a specific GameVersion or equivalent rule-set identifier.

---

#### RuleChunk

Represents a searchable part of a rule document.

Possible fields:

- id
- documentId
- gameVersionId
- content
- sectionTitle
- pageNumber
- chunkIndex
- embedding
- metadata

Chunks must preserve enough metadata to identify:

- game
- version/edition
- document
- section
- page when available

---

#### Expansion

Design the model so expansions can be supported later.

An expansion may have its own:

- metadata
- version
- rule documents
- rule chunks

Do not implement complex expansion behavior yet. However, avoid a schema that assumes every session contains only one immutable base-game ruleset forever.

---

### Updated game session model

GameSession should support concepts such as:

- id
- gameId
- gameVersionId
- playerCount
- teachingDepth
- sessionStage
- currentTeachingSection
- currentSetupStep
- status
- createdAt
- endedAt

The client should normally provide:

- gameId
- playerCount
- teachingDepth

The backend resolves:

- gameVersionId

Example session creation request:

{
  "gameId": "...",
  "playerCount": 4,
  "teachingDepth": "STANDARD"
}

The application then resolves the default active GameVersion.
Use teachingDepth QUICK / STANDARD / DETAILED, defaulting to STANDARD. SessionStage is
TEACHING / SETUP / PLAYING / COMPLETED; optional CANCELLED and PAUSED may be added later.
Session lifecycle status and workflow stage have separate responsibilities. Teaching and setup
progress must be persisted explicitly, including the return position when setup interrupts a lesson.
Changing the catalog default must never repin an existing session's GameVersion.

#### TeachingFlow

A version-bound TeachingFlow owns ordered TeachingSections. The structure and teaching-depth
applicability are application-managed data, not generated anew by an LLM for each session.

### Teaching section model

A TeachingSection may contain:

- id
- teachingFlowId
- key
- order
- title
- explanation
- teachingDepth applicability
- optional examples
- optional tips
- optional references
- optional setupTrigger
- active

Example:

{
  "key": "OBJECTIVE",
  "order": 2,
  "title": "How to Win",
  "explanation": "The first player to reach 10 victory points wins the game."
}

Teaching progress must be stored in the GameSession. Do not rely on the LLM's conversation history to determine what comes next.

#### ConversationMessage

Persist id, sessionId, role (USER/ASSISTANT/SYSTEM), content, and timestamp. Keep bounded
recent context behind SessionContextService, with a future summarization boundary.
Conversation messages must not replace persisted teaching/setup progress.

### Setup flow

Conceptually:

GameVersion ↓ SetupFlow ↓ SetupSteps

A SetupStep may contain:

- id
- setupFlowId
- key
- order
- title
- instruction
- optional notes
- optional warnings

The session stores which setup step is currently active.

The API should support:

- start setup
- get current step
- next step
- previous step
- repeat step
- restart setup
- complete setup

When SetupFlow completes, TeachingFlow should resume at the correct section.

## 6. RAG design

### Session-aware answering flow

The rule answering pipeline should conceptually be:

Customer Question ↓ Load GameSession ↓ Determine GameVersion ↓ Create Query Embedding ↓ Search Relevant RuleChunks ↓ Filter by GameVersion ↓ Apply expansion filters if relevant ↓ Rank / rerank results ↓ Evaluate retrieval confidence ↓ Provide evidence + question + conversation context to LLM ↓ Generate grounded answer ↓ Return answer + citations

RAG infrastructure must remain generic for all games.

Do not implement:

if game == CATAN

inside shared RAG infrastructure.

This session-aware flow applies from Milestone 6 onward. Milestone 4 first exposes grounded
questions using a selected Game and resolved default GameVersion, without implementing
sessions early. TeachingFlow determines lesson order; retrieval supplies rule evidence. Semantic retrieval uses embeddings for similarity search. Keep vector-specific SQL behind the retrieval service.

## 7. RAG response contract

A generated rule answer should conceptually return:

{
  "answer": "...",
  "confidence": "HIGH",
  "sources": [
    {
      "document": "Catan Rulebook",
      "section": "Trade and Build",
      "page": 7
    }
  ]
}

Confidence may use:

- HIGH
- MEDIUM
- LOW

Confidence must not be fabricated randomly. Base confidence on retrieval signals and whether the retrieved evidence sufficiently supports the answer.

If evidence is weak, the assistant must prefer saying:

"I couldn't verify that confidently from the available rules."

rather than hallucinating. Never instruct the LLM to invent an answer when evidence is missing.

---

## 8. Prompting rules

Create prompts programmatically rather than scattering giant prompt strings throughout controllers. Create a dedicated prompt builder or GameMasterPromptService.

The LLM system instruction should include concepts like:

"You are a board game rules assistant. Answer using the supplied rule excerpts. Do not rely on unsupported assumptions. If the supplied rules do not contain enough information, state that you cannot verify the answer. Explain rules simply and concisely. The users may be complete beginners. Do not invent game rules. Respect the specified game edition and expansions. When useful, explain why the rule applies.

Do not claim a source that was not provided."

The retrieved evidence should be clearly separated from user content. Treat rule documents as data, not executable instructions. Protect against prompt injection contained inside imported documents.

---

## 9. Document ingestion

Create a generic document-ingestion pipeline. It must work for rule documents belonging to any supported GameVersion.

The pipeline should conceptually perform:

Rule document
    ↓
Resolve GameVersion
    ↓
Text extraction
    ↓
Text cleanup
    ↓
Section detection where possible
    ↓
Chunking
    ↓
Embedding generation
    ↓
Store RuleChunks

The ingestion service must not be specific to Catan. Catan is simply the first dataset used to test the pipeline.

A future workflow should be able to do something like:

Import Azul rules
→ specify Azul GameVersion
→ process document
→ create embeddings
→ rules become searchable

without implementing a new ingestion pipeline.

---

Create a document ingestion pipeline.

The ingestion pipeline should conceptually perform:

Rule document
    ↓
Text extraction
    ↓
Text cleanup
    ↓
Section detection if possible
    ↓
Chunking
    ↓
Embedding generation
    ↓
Store RuleChunks

Chunking should not simply split every N characters.

Prefer semantic boundaries such as:

- headings
- paragraphs
- sections

Use overlapping chunks when helpful. Keep chunk size configurable. Do not make ingestion dependent on HTTP controllers. Create an application service that can later be triggered through CLI, admin UI, API, or background processing. For the initial version, it is acceptable to support ingestion from normalized text/Markdown if PDF parsing is not yet implemented. Do not commit copyrighted commercial rulebooks to a public Git repository unless their license permits redistribution.

Instead, provide:

- an import mechanism
- sample/demo rule content that is legally safe
- documentation explaining how the developer can supply rule files locally

---

## 10. Phase 1 — basic rule knowledge

Goal: generic rule ingestion, semantic retrieval and grounded answers using Game, GameVersion,
RuleDocument and RuleChunk. Catan is the first permitted reference dataset. This phase proves
question → relevant rule retrieval → grounded answer; the full teaching experience arrives later. Implement the generic platform first and use Catan as the first supported game.

Required behavior:

1. Create a Game.
2. Create a GameVersion.
3. Attach rule documents to that version.
4. Ingest the documents.
5. Generate embeddings.
6. Search the relevant rule chunks.
7. Ask questions.
8. Generate answers with citations.

Endpoints may include something conceptually similar to:

POST /api/v1/games

POST /api/v1/games/{gameId}/versions

POST /api/v1/game-versions/{gameVersionId}/documents

POST /api/v1/games/{gameId}/questions

For the initial implementation, when:

POST /api/v1/games/{gameId}/questions

is called, the backend may automatically use that game's default GameVersion.

Example:

POST /api/v1/games/{catanId}/questions

{
  "question": "What happens when a seven is rolled?"
}

Response:

{
  "answer": "...",
  "confidence": "HIGH",
  "sources": [...]
}

The API should not require customers to specify an edition when a default supported version exists.

---

Implement this first.

Goal:

A user can submit a text question about Catan and receive a grounded answer.

Required endpoints should include something conceptually similar to:

POST /api/v1/games

POST /api/v1/games/{gameId}/documents

POST /api/v1/games/{gameId}/questions

Example:

POST /api/v1/games/{gameId}/questions

{
  "question": "What happens when a seven is rolled?"
}

Response:

{
  "answer": "...",
  "confidence": "HIGH",
  "sources": [...]
}

Requirements:

- PostgreSQL
- pgvector
- embeddings
- vector similarity search
- LLM integration
- source citations
- validation
- error handling
- tests

Do not proceed to later phases until this works reliably.

---

## 11. Phase 2 — production-quality RAG

Improve:

- GameVersion filtering
- metadata filtering
- document types
- language metadata
- configurable top-K
- similarity threshold
- duplicate suppression
- reranking abstraction
- confidence calculation
- evaluation datasets
- retrieval tests
- low-confidence fallback

Retrieval must be testable independently from generation.

Use version-scoped filtering and retain document/language metadata; optional expansion
filtering must remain possible. Version filtering does not require a customer edition selector.
Provide automated evaluation data (for example src/test/resources/evaluation/catan-rule-questions.json)
with questions, expectedTopics and mustRetrieveSection. Diagnose retrieval separately from
prompt generation and LLM response; do not test the whole system exclusively through an LLM.

## 12. Phase 3 — game sessions, teaching flow and conversation

Phase 3 introduces the actual Game Master experience.

Implement:

- GameSession
- ConversationMessage
- TeachingFlow
- TeachingSection
- teaching progress
- teachingDepth
- SessionStage
- session context
- questions during lessons

The customer must be able to:

1. create a session
2. begin teaching
3. receive the first lesson
4. continue to the next lesson
5. move backward
6. repeat a lesson
7. ask a question while learning
8. receive a RAG-grounded answer
9. resume teaching from the same location
10. complete teaching sections
11. transition eventually toward gameplay

Phase 3 should not yet depend on voice. Text interactions must work first.

### Conversation context

Conversation context must be maintained for the active session.

Example:

Customer:

"How do I get resources?"

Game Master answers.

Customer:

"What if the robber is there?"

The system should understand that the second question refers to the previous topic. Persist ConversationMessage records. Do not indefinitely send the entire conversation history to the LLM. Use a bounded context strategy.

A future summarization mechanism should be possible without redesigning the session system.

### Session API and context responsibilities

Planned POST /api/v1/sessions request:

```json
{"gameId":"...","playerCount":4,"teachingDepth":"STANDARD"}
```

Return a session ID. Validate player count, load the selected Game, resolve the active default
GameVersion and pin it to the session. Omitted teachingDepth defaults to STANDARD.
The client does not normally send gameVersionId. Planned POST /api/v1/sessions/{sessionId}/messages
accepts a question without advancing or resetting the active lesson or setup step.

SessionContextService loads game, pinned GameVersion, player count, teaching depth, stage,
teaching/setup progress, applicable expansions and bounded recent messages. Controllers must
not assemble AI context themselves. A clarification answer returns to the same lesson;
Continue Lesson resumes from the persisted position. Catalog default changes do not alter it.

Milestone 6 implements teaching/session state and navigation. Actual SetupFlow execution and
its teaching interruption/resume transition arrive in Milestone 7; do not implement that early.

## 13. Phase 3 — teaching experience

### Teaching flow

Create a generic TeachingFlow system.

Conceptually:

GameVersion ↓ TeachingFlow ↓ TeachingSections

A TeachingFlow belongs to a GameVersion. Different games may have completely different teaching structures. Do not hardcode a universal Catan teaching sequence into shared application logic.

For example, Catan might have:

1. Introduction
2. Objective
3. Components
4. Setup
5. Resource Production
6. Trading
7. Building
8. Robber
9. Turn Structure
10. Winning

Another game may have completely different sections. The teaching engine must remain generic.

### Teaching content and RAG

TeachingFlow and RAG must be separate responsibilities.

TeachingFlow answers:

> What should the customer learn next?

RAG answers:

> What verified rule information supports this explanation or question?

The architecture should conceptually be:

TeachingFlow
    ↓
Teaching Section
    ↓
Rule references / relevant knowledge
    ↓
RAG when needed
    ↓
LLM
    ↓
Beginner-friendly explanation

Do not generate an entire game's teaching flow dynamically every time a customer starts a session. The teaching structure should be application-managed data. The LLM may be used to turn verified content into natural beginner-friendly language. The LLM must not invent rules.

### Teaching style

The Game Master should speak like a helpful café Game Master teaching people who may have never played before.

Explanations should be:

- conversational
- beginner-friendly
- concise
- structured
- practical
- easy to follow at a physical table

Avoid reading formal rulebook text word-for-word.

Prefer:

"Your goal is to be the first player to reach 10 victory points. You mainly earn points by building settlements and cities."

instead of reproducing dense official wording. Underlying facts must remain grounded in verified rules.

### Progressive teaching

Do not explain the entire game in one giant response. Teaching should be progressive.

Example:

Game Master:

"Welcome to Catan! You're competing to build the most successful settlements on the island. The first player to reach 10 victory points wins.

Next, I'll explain the pieces you'll be using."

Customer chooses:

Continue

The Game Master proceeds to the next TeachingSection.

The customer should have controls/actions such as:

- Continue
- Previous
- Repeat
- Explain Again
- Ask a Question

The exact wording may differ in the final UI.

### Questions during teaching

Customers must be able to ask questions at any point.

Example flow:

Teaching Section ↓ Customer asks question ↓ Question sent through Game Master Q&A pipeline ↓ RAG retrieves relevant rules ↓ LLM produces grounded answer ↓ Answer displayed/spoken ↓ Teaching position remains unchanged ↓ Customer selects Continue Lesson

The system should preserve:

- game
- GameVersion
- teaching progress
- setup progress
- conversation context

### Ready-to-play transition

Teaching must clearly indicate when customers know enough to begin playing.

Example:

"That's everything you need to get started. You're ready to play.

I'll stay here while you play, so if you're unsure about a rule, just ask me."

The session then transitions to:

PLAYING

The customer should not need to manually create another session. The same session continues.

### Playing / Q&A experience

During PLAYING, rule Q&A becomes the primary interaction.

Examples:

"What can I do on my turn?"

"Can I trade now?"

"What happens when I roll a seven?"

"How many cards do I discard?"

"Can I build here?"

"What happens next?"

Questions must use:

- current GameSession
- resolved GameVersion
- active expansions if implemented
- recent conversation context
- RAG retrieval
- verified source documents

The assistant should return source citations when appropriate.

If evidence is insufficient, prefer:

"I couldn't verify that confidently from the available rules."

Do not hallucinate a rule.

## 14. Phase 4 — guided setup integration

### Guided setup

Setup is part of the teaching experience. The Game Master should guide the customer through physically preparing the selected game.

Example:

Game Master:

"Now let's set up the board." ↓ SetupFlow starts ↓ Customer completes setup steps ↓ Game Master:

"Great. Your board is ready. Now I'll explain how your turn works."

Setup must use deterministic application-managed state. Do not rely on an LLM to remember which setup step comes next. Phase 4 introduces SetupFlow and integrates it into TeachingFlow.

Implement:

- SetupFlow
- SetupStep
- setup progress
- setup navigation
- restart support
- integration between TeachingFlow and SetupFlow

Example:

Teaching: Components ↓ Teaching section triggers setup ↓ SessionStage = SETUP ↓ SetupFlow begins ↓ Customer completes setup ↓ SetupFlow finishes ↓ SessionStage = TEACHING ↓ Teaching resumes

Phase 4 is complete when a new player can be guided through the physical setup of the supported game.

### Setup API

Planned version-bound setup operations include:

- GET /api/v1/sessions/{id}/setup — current step and progress
- POST /api/v1/sessions/{id}/setup/start
- POST /api/v1/sessions/{id}/setup/next
- POST /api/v1/sessions/{id}/setup/previous
- POST /api/v1/sessions/{id}/setup/repeat
- POST /api/v1/sessions/{id}/setup/restart
- POST /api/v1/sessions/{id}/setup/complete

Persist the current/completed steps and teaching return position. Setup completion moves
SETUP back to TEACHING at the correct section, not directly to PLAYING. The same generic
engine must execute another game's configured steps without Catan-specific branches.

## 15. Phase 5 — voice and minimal demo UI

### Phase 5 — voice

Add voice on top of the existing Game Master pipeline. Do not create a separate voice-specific rules system.

Create provider abstractions:

SpeechToTextClient

TextToSpeechClient

Voice flow:

Customer Audio ↓ SpeechToTextClient ↓ Transcription ↓ Existing Game Master Pipeline ↓ Text Answer ↓ TextToSpeechClient ↓ Audio Answer

For the MVP, use push-to-talk interaction.

Do not implement:

- always listening
- wake-word detection
- continuous microphone monitoring

Voice must be optional. Text interaction must continue working independently.

### Phase 5 — minimal demo UI

Phase 5 must also include a minimal browser-based user interface.

The UI is required for:

- manual testing
- product demonstration
- portfolio demonstration
- experiencing the full Game Master flow without Swagger/Postman

This is not yet the final polished café UI. Keep the UI lightweight so the project remains primarily backend-focused.

Preferred implementation:

- Spring Boot
- Thymeleaf
- HTML
- CSS
- minimal JavaScript

Do not introduce React, Vue, Angular, or a separate frontend project unless there is a strong technical reason. The UI must use the same backend application logic as REST endpoints.

Never duplicate:

- game logic
- teaching progression
- setup progression
- RAG logic
- session logic
- AI orchestration

inside frontend JavaScript.

### Minimal UI — start screen

The initial UI should provide:

BOARD GAME MASTER

Choose Game:

[Catan ▼]

Players:

[4]

Teaching Style:

[Quick]
[Standard]
[Detailed]

[Start Game]

STANDARD should be selected by default. The game list must come from the backend. Do not hardcode Catan into the page.

### Minimal UI — teaching screen

After the session starts, show:

- selected game
- current lesson
- teaching progress
- Game Master explanation
- navigation controls
- question input

Conceptually:

Catan

Learning the Game

Progress:

✓ Introduction
✓ Objective
→ Components
  Setup
  Core Rules
  Turn Structure
  Important Rules
  Ready to Play

Game Master:

"Before we set up the board, let's look at the important pieces you'll be using..."

[Previous]

[Repeat]

[Continue]

Ask the Game Master:

[Type a question...]

[Ask]

Questions should appear as temporary conversation interactions without destroying the lesson state.

After the answer, provide:

[Continue Lesson]

### Minimal UI — setup screen

When TeachingFlow reaches setup:

Catan Setup

Step 3 of 8

Choose Player Colors

"Each player chooses one available color and takes the matching pieces."

[Previous]

[Repeat]

[Done / Next]

Optional progress:

3 / 8

When setup finishes:

"Great. Your game is set up."

[Continue Learning]

### Minimal UI — ready to play

At the end of teaching:

Game Master:

"That's everything you need to start playing. You're ready to begin.

I'll stay here while you play. If you're unsure about anything, just ask me."

[Start Playing]

The session transitions to:

PLAYING

### Minimal UI — playing screen

During gameplay, show:

Catan

Game In Progress

Game Master:

"You're ready to play. Ask me whenever you need help."

Conversation:

Customer:

"What happens when I roll a seven?"

Game Master:

"When a seven is rolled..."

Source:

Official Rulebook — Robber

Input:

[Ask a question...]

[Send]

Voice:

[Hold to Speak]

Additional actions:

[Review Rules]

[End Game]

### Review rules

During gameplay, customers should be able to review previously taught material.

For example:

Review Rules

- Objective
- Components
- Setup
- Resource Production
- Trading
- Building
- Robber
- Winning

Opening a review section should not reset the GameSession.

The customer must be able to return to:

Game In Progress

### UI voice interaction

The Phase 5 UI should provide a simple push-to-talk experience if browser capabilities and the chosen architecture support it.

Conceptually:

[Hold to Speak] ↓ record audio ↓ send audio to backend ↓ Speech-to-Text ↓ display transcription ↓ Game Master pipeline ↓ display text answer ↓ Text-to-Speech ↓ play generated answer

The UI should show transcription so errors can be understood during testing. If recording directly from the browser introduces unnecessary complexity, audio upload may initially be supported during development, but the final Phase 5 demo should aim for a practical push-to-talk interaction.

### UI error states

The UI must gracefully handle:

- backend unavailable
- GameSession not found
- AI provider unavailable
- no rules loaded
- low RAG confidence
- speech transcription failure
- TTS failure
- invalid audio
- empty question
- invalid player count

Do not expose stack traces. Provide understandable messages.

Example:

"I couldn't verify that rule from the available documents."

or:

"I couldn't understand the audio. Please try again."

### Voice API and resilience

POST /api/v1/sessions/{sessionId}/voice accepts audio and returns transcription, text answer,
citations and playable audio or structured audio metadata. Reuse the session/text application
pipeline. Validate audio types/sizes, handle timeouts, provider failures and empty transcriptions,
and keep text available when STT/TTS fails. Do not add queues without a demonstrated need.
The final demo should provide practical push-to-talk; audio upload may be an interim development aid.

## 16. API design

Use RESTful APIs. Use DTOs. Do not expose JPA entities directly. Use Bean Validation. Return consistent errors.

Example:

{
  "code": "GAME_NOT_FOUND",
  "message": "Game was not found",
  "timestamp": "...",
  "path": "/api/v1/..."
}

Create global exception handling. Use proper HTTP status codes. Document APIs using OpenAPI.

Keep endpoints versioned under:

/api/v1

---

## 17. Database migrations

Use Flyway. All database changes must be migrations. Do not rely on Hibernate automatic schema creation for production behavior. Local development may validate the schema.

Prefer:

ddl-auto=validate

after migrations exist. Add the pgvector extension through a migration or documented database initialization.

Create indexes appropriate for:

- foreign keys
- session lookup
- game slug
- rule document filtering
- vector similarity retrieval

Explain vector index choices in documentation.

---

## 18. Security

This is initially a local/portfolio application, but maintain good practices.

Never commit:

- API keys
- database passwords
- provider secrets

Use environment variables.

Provide:

.env.example

but never commit:

.env

Validate uploaded files. Set reasonable upload limits. Treat imported documents and user messages as untrusted input. Prevent document text from overriding system prompts. Do not expose stack traces to API consumers.

---

## 19. AI provider abstraction

Do not write business code such as:

openAiClient.chat(...)

inside controllers or domain services. Use interfaces.

Example:

public interface LlmClient {
    LlmResponse generate(LlmRequest request);
}

public interface EmbeddingClient {
    Embedding generate(String text);
}

Implement provider adapters separately. The application should theoretically be able to replace one provider with another without rewriting the Game Master domain logic.

Same for:

SpeechToTextClient
TextToSpeechClient

---

## 20. Configuration

Externalize configuration.

Examples:

boardgame.rag.top-k
boardgame.rag.similarity-threshold
boardgame.rag.chunk-size
boardgame.rag.chunk-overlap
boardgame.ai.model
boardgame.ai.temperature

For rule answers, use low randomness. Do not bury important constants in application code.

---

## 21. Observability

Use structured logging.

Include useful identifiers where appropriate:

- request ID
- session ID
- game ID

Never log:

- API secrets
- entire audio files
- sensitive credentials

Log important pipeline timings, such as:

- embedding generation duration
- retrieval duration
- LLM duration
- overall response duration

Create basic metrics if convenient, but do not add a huge observability stack during the MVP.

---

## 22. Testing requirements

Testing is mandatory.

Create:

### Unit tests

For:

- setup workflow
- confidence calculation
- prompt building
- chunking
- domain validation
- session state transitions

### Repository tests

Use Testcontainers with PostgreSQL + pgvector. Test vector retrieval against known embeddings where practical.

### Integration tests

Test:

- create game
- create/import document
- create session
- ask question
- guided setup progression

Mock external LLM/speech APIs in ordinary integration tests. Do not make the default test suite require paid AI calls. Create optional manual/AI evaluation tests separately.

---

## 23. Error handling

Explicitly handle:

- game not found
- session not found
- unsupported game edition
- invalid player count
- no rules available
- insufficient retrieval confidence
- embedding provider unavailable
- LLM provider unavailable
- transcription failure
- TTS failure
- malformed requests

An AI provider outage should not cause a cryptic HTTP 500. Return meaningful application errors.

---

## 24. Resilience

External APIs are unreliable.

Add sensible:

- timeouts
- retry policy where safe
- graceful error handling

Do not automatically retry requests in ways that can cause duplicate side effects. For AI requests, a small bounded retry may be acceptable for transient failures. Do not create complicated resilience infrastructure unless justified.

---

## 25. README

The README must make the architecture clear:

Board Game Master is a multi-game platform. Catan is currently the first/reference supported game.

Avoid describing the application as:

"Catan AI Assistant"

Instead describe it as:

"AI Board Game Master"

or:

"AI-powered board game assistant platform"

The README should explicitly state:

> The platform is designed to support multiple board games. Catan is used as the initial reference implementation and test dataset.

Also explain how a future game would be added.

Conceptually:

1. Register Game
2. Register GameVersion
3. Import permitted rule documents
4. Generate rule embeddings
5. Define setup flow
6. Define a version-bound TeachingFlow and its teaching-depth content
7. Add retrieval evaluation cases
8. Publish/activate the game

The README must also document the problem, planned versus implemented features, architecture diagram, RAG flow, stack, local setup, environment variables, database startup, tests, API examples, phases, and deferred future work. Do not present planned commands or features as already operational.

## 26. Scope and non-goals

Implement Phases 1–5 only. Physical robot hardware, computer vision, café inventory/table management, recommendations, deterministic board-state validation or game-rules engines, staff escalation, analytics, polished production café UI, always-listening microphones, and wake-word detection are deferred. No microservices, Kubernetes, event buses, Redis, or queues without a demonstrated need.

## 27. Development process

Work strictly one milestone at a time. Before implementation, inspect existing code, read this specification and docs/DEVELOPMENT_STATUS.md, summarize the intended scope, and identify validation. Implement only the requested milestone. Resolve relevant test failures before declaring it complete. Report changes, validation, limitations, and manual demonstration steps. Update development status honestly. Stop after the current milestone; do not automatically begin the next one. Do not claim tests passed if they were not run.

## 28. Implementation order

### Milestone 0 — Project Bootstrap

Create the Java 21+ Spring Boot Gradle project and wrapper, feature-oriented packages, configuration profiles, Docker Compose PostgreSQL + pgvector service, Flyway initialization, schema validation, health endpoint, OpenAPI configuration, consistent API error foundation, and basic startup tests. Document setup and environment configuration. No game-specific functionality.
Acceptance: local database starts, migrations run, application starts, health/API documentation are reachable, and bootstrap tests pass without paid AI calls.

### Milestone 1 — Game Domain

Implement generic Game and GameVersion entities, repositories, services, REST APIs, validation, migrations, and tests. Create Catan as data with one supported default version. Validate version ownership and default resolution.
Acceptance: games and versions can be created/read; invalid players/configuration fail clearly; customers can resolve a default version; no Catan-specific Java services or entities.

### Milestone 2 — Rule Documents

Implement version-scoped RuleDocument and RuleChunk persistence, permitted local text/Markdown import, cleanup, configurable semantic chunking, source metadata, validation, and ingestion tests. PDF extraction is optional.
Acceptance: imported content yields ordered traceable chunks belonging to the correct version; malformed input fails clearly; no copyrighted rulebooks are committed.

### Milestone 3 — Embeddings and Vector Search

Implement EmbeddingClient and its adapter, embedding storage, pgvector retrieval behind RuleRetrievalService, version-scoped search, configuration, migrations/indexes, and Testcontainers tests.
Acceptance: known embeddings retrieve expected chunks; other games/versions are excluded; provider failures are meaningful.

### Milestone 4 — RAG Question Answering

Implement LlmClient and adapter, prompt builder, question endpoint with default-version resolution, grounded generation, source references, evidence-based confidence, and insufficient-evidence fallback.
Acceptance: Phase 1 works end to end with permitted Catan input; unsupported answers are not invented; default tests use mock providers.

### Milestone 5 — RAG Improvements

Implement Phase 2 metadata filtering, configurable top-K/thresholds, duplicate suppression, ranking/reranking boundary, evaluation fixtures, and retrieval diagnostics.
Acceptance: evaluate retrieval separately from generation; ruleset isolation and weak-evidence fallback are verified; document evaluation results.

### Milestone 6 — Game Sessions + Teaching Flow

Implement Phase 3 GameSession, ConversationMessage, SessionStage, teachingDepth (QUICK/STANDARD/DETAILED with STANDARD default), stable resolved GameVersion, bounded context and SessionContextService. Add version-bound TeachingFlow/TeachingSection data and persisted teaching progress, continue/previous/repeat/explain-again controls, questions during lessons and resume behavior. Keep workflow state application-owned.
Acceptance: customers create a session with gameId/playerCount and optional teachingDepth; teaching starts progressively; questions and contextual follow-ups preserve the current lesson; navigation and depth selection are tested; catalog default changes do not alter a pinned ruleset. Setup integration remains Milestone 7.

### Milestone 7 — Guided Setup Integrated with Teaching

Implement Phase 4 version-bound SetupFlow/SetupStep, persisted progress, start/current/complete/next/previous/repeat/restart operations, teaching-section setup triggers and deterministic interruption/resume transitions. Catan setup is reference data.
Acceptance: the generic engine executes configured setup, preserves progress during Q&A, handles navigation/restart boundaries, and returns SETUP to TEACHING at the saved lesson. Readiness then transitions the same session to PLAYING; no LLM owns workflow state.

### Milestone 8 — Voice + Minimal Demo UI

Implement Phase 5 SpeechToTextClient/TextToSpeechClient adapters and push-to-talk using the existing session/text pipeline. Validate audio, timeouts, provider failures and empty transcription. Add a lightweight Spring Boot/Thymeleaf browser UI with HTML/CSS/minimal JavaScript, backend-loaded games, player count, teaching depth, lesson/setup navigation, progress-preserving questions, ready-to-play transition, gameplay Q&A/citations/history, transcription/audio, rule review and end session. Keep all business logic in shared backend services.
Acceptance: the complete teaching → setup → resumed teaching → playing journey can be demonstrated without Swagger/Postman; questions and review do not reset progress; voice displays transcription and plays an answer using the same pipeline; errors are understandable and text remains usable independently. Tests cover backend orchestration and UI integration using mock providers. Phase mapping: Phase 1 = Milestones 1–4 (after bootstrap); Phase 2 = Milestone 5; Phase 3 = Milestone 6; Phase 4 = Milestone 7; Phase 5 = Milestone 8 (voice and minimal demo UI).

## 29. Acceptance criteria

Phase 5 is considered complete when this complete experience works:

1. Developer starts PostgreSQL through Docker Compose.
2. Spring Boot starts successfully.
3. Developer opens the browser UI.
4. Supported games are loaded from the backend.
5. Catan appears as the first supported game.
6. Customer selects Catan.
7. Customer selects four players.
8. Customer selects STANDARD teaching depth.
9. Customer starts the session.
10. Backend resolves the appropriate GameVersion.
11. TeachingFlow starts.
12. Game Master introduces Catan.
13. Game Master explains the objective.
14. Customer continues.
15. Game Master explains important components.
16. TeachingFlow reaches the setup section.
17. SetupFlow starts.
18. Customer progresses through setup steps.
19. Setup completes.
20. TeachingFlow resumes.
21. Game Master explains the core rules.
22. Game Master explains how a turn works.
23. Customer asks a clarification question during teaching.
24. The current teaching position remains saved.
25. RAG retrieves relevant rule information.
26. Game Master answers with grounded information.
27. Source citation is available.
28. Customer chooses Continue Lesson.
29. Teaching resumes correctly.
30. Teaching completes.
31. Game Master tells the customer they are ready to play.
32. Session transitions to PLAYING.
33. Customer asks a text rule question.
34. RAG retrieves relevant rules.
35. Game Master answers with a citation.
36. Customer asks a contextual follow-up.
37. Conversation context is correctly understood.
38. Customer uses voice interaction.
39. Audio is transcribed.
40. The transcription is displayed.
41. The same Game Master pipeline handles the question.
42. The response is displayed.
43. Text-to-Speech generates an audio response.
44. Audio is played to the customer.
45. Customer can review previously taught rules.
46. Customer returns to the active game.
47. Customer ends the session.
48. Automated tests pass.
49. No secrets are committed.
50. The entire workflow can be demonstrated without Swagger or Postman. Additional invariants: shared engines contain no Catan-specific assumptions; adding another game does not require rewriting ingestion, retrieval, teaching, setup, sessions or voice. README explains the complete demonstration and clearly separates implemented features from planned requirements.

## 30. Completion and validation

A milestone is complete only when its implemented behavior satisfies its acceptance criteria and relevant tests pass. Use unit tests, PostgreSQL + pgvector Testcontainers tests, and Spring Boot integration tests as appropriate. Default tests require no paid provider calls. Keep optional real-provider evaluation separate. Document any unverified behavior rather than marking it complete.

## 31. Adding a second game

Register Game and supported GameVersion metadata; import permitted documents; generate embeddings; configure version-bound TeachingFlow/TeachingSection and SetupFlow/SetupStep data; add evaluation/test fixtures; activate the supported version. Core ingestion, RAG, sessions, setup execution, and voice must be reusable without redesign. Expansion support must remain possible without implementing complex expansion behavior now.

## 32. Next implementation task

Determine progress from docs/DEVELOPMENT_STATUS.md. Implement only the milestone explicitly requested by the user; a completed milestone does not authorize starting the next. Documentation updates do not authorize source changes. Preserve working architecture unless a requirement actually requires a change. Build for many games; validate with one.

## 33. Portfolio expectation and architectural principles

### Portfolio expectation

By Phase 5, the repository should demonstrate:

- Java
- Spring Boot
- REST APIs
- PostgreSQL
- pgvector
- Flyway
- schema design
- embeddings
- semantic retrieval
- RAG
- LLM integration
- provider abstractions
- GameVersion handling
- session state
- conversation context
- teaching workflows
- setup state machines
- voice integration
- Thymeleaf/browser UI
- automated testing
- Testcontainers
- Docker
- validation
- API error handling
- source citations
- confidence handling

The demo should let someone complete the teaching, setup and gameplay flow in the browser.

### Architectural principles

The following principles must remain true:

#### Build for many games, validate with one.

Catan is the first supported game. Catan must not define the architecture.

#### Teaching state belongs to the application.

The LLM must not be responsible for remembering which lesson comes next.

#### Setup state belongs to the application.

The LLM must not be responsible for remembering setup progress.

#### Rules come from verified sources.

Use RAG rather than relying only on model memory.

#### LLM explains; application orchestrates.

Use the LLM for natural explanations and conversation. Use backend services/state machines for deterministic workflow.

#### Voice is an interface.

Voice must reuse the same Game Master pipeline used by text.

#### UI is thin.

Do not put domain logic into Thymeleaf templates or frontend JavaScript.

# Board Game Master — Project Specification

Authoritative master specification. Latest multi-game revisions supersede the earlier Catan-only model. This repository starts with documentation and an empty source skeleton; no milestone is implemented.

# 1. PRODUCT GOAL

The system must be designed as a **multi-game board game platform from the beginning**.

The first fully supported game for development and testing will be **Catan**, but the architecture must not contain Catan-specific assumptions in shared application logic.

Adding a second game in the future should primarily involve:

- creating the game's metadata
- importing its rule documents
- configuring its supported editions/versions
- defining its teaching content
- defining its setup flow
- adding evaluation/test cases

Adding another game should **not require redesigning the RAG pipeline, session system, conversation system, or core backend architecture**.

For the initial MVP, Catan is used as the reference implementation proving that the generic platform works.

A customer should eventually be able to:

1. Select a supported board game.
2. Specify the number of players.
3. Choose a mode:
   - Quick Start
   - Learn Game
   - Ask Rules
4. Start a game session.
5. Ask natural-language questions about the game.
6. Receive answers grounded in the correct rule documents.
7. Ask follow-up questions while maintaining conversation context.
8. Follow guided setup instructions.
9. Later, interact with the same system through voice.

For example:

Customer selects:

Catan

Player count:

4

Mode:

Quick Start

Then asks:

"Can I trade after building?"

The backend must:

1. determine which game/rule set belongs to the session
2. retrieve relevant rules
3. provide those rules to the LLM
4. generate a grounded answer
5. return source references

The customer should **not normally be required to manually choose an edition/version**.

Edition/version information belongs primarily to the game's backend configuration.

For example, internally the system may know:

Game:
Catan

Edition:
5th Edition

Language:
English

Rules Version:
2025

The customer simply sees:

Catan

The backend then automatically searches the appropriate rule set.

If multiple editions of the same game are intentionally made available later, the application may expose an edition selector, but edition selection must not be required for the initial customer experience.

---


The first supported game should be **Catan**.

A user should eventually be able to do something like:

1. Start a game session.
2. Select Catan and its edition.
3. Specify number of players.
4. Choose a mode:
   - Quick Start
   - Learn Game
   - Ask Rules
5. Ask:

   "Can I trade after building?"

6. The backend searches the relevant rule documents using RAG.
7. The LLM answers using retrieved rules rather than relying only on model knowledge.
8. The response contains citations or references to the source rule sections.
9. Follow-up questions preserve conversational context.

Example:

User:
"How do I get resources?"

Assistant:
Explains resource production.

User:
"What if the robber is there?"

The assistant should understand what "there" refers to because the session maintains conversation context.

The application should also support step-by-step guided game setup.

Later in Phase 5, voice input and output should be added.

---

# 2. ENGINEERING PRIORITIES

Prioritize, in order:

1. Correctness
2. Maintainability
3. Testability
4. Clear architecture
5. Reliability
6. Developer experience
7. Performance
8. Fancy features

Do not build a toy demo.

Build this as a serious portfolio project that could reasonably evolve into a real product.

Avoid unnecessary abstractions, microservices, distributed systems, Kubernetes, event buses, and premature optimization.

Use a **modular monolith**.

---

# 3. REQUIRED TECH STACK

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

The platform must remain game-independent at the infrastructure level.

RAG, sessions, conversations, voice, document ingestion, and retrieval must work generically for any supported game.

---


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
- Gradle or Maven; prefer Gradle unless the repository already uses Maven

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

Infrastructure:

- Docker Compose for local PostgreSQL with pgvector

Optional:

- Redis may be introduced only if there is a demonstrated need.
- Do not add Redis merely because it is common in production systems.

---

# 4. ARCHITECTURE

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

Do not force every module to contain every subfolder if it does not need them.

Keep domain logic independent from external AI SDKs.

Use interfaces at integration boundaries.

Examples:

LlmClient
EmbeddingClient
SpeechToTextClient
TextToSpeechClient

Provider-specific implementations should live in infrastructure code.

---

# 5. DOMAIN MODEL

Design the domain so multiple board games can be supported without changing core application architecture.

### Game

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

Do not place edition directly on the Game if doing so prevents one game from having multiple versions.

Prefer separating the logical game from its rule version.

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

### GameVersion

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

and the backend resolves the default supported version automatically.

Later, a café configuration could associate a physical copy with a specific GameVersion.

For example:

Catan Copy #1
→ Catan 5th Edition / English

Catan Copy #2
→ Catan 5th Edition / Indonesian

Do not require this café inventory functionality during Phases 1–5.

---

### RuleDocument

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

### RuleChunk

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

### Expansion

Design the model so expansions can be supported later.

An expansion may have its own:

- metadata
- version
- rule documents
- rule chunks

Do not implement complex expansion behavior yet.

However, avoid a schema that assumes every session contains only one immutable base-game ruleset forever.

---

### GameSession

Represents one customer/table play session.

Possible fields:

- id
- gameId
- gameVersionId
- playerCount
- mode
- status
- currentSetupStep
- createdAt
- endedAt

The customer may provide only:

- gameId
- playerCount
- mode

The application should resolve the correct GameVersion automatically when there is one configured default version.

Do not make the customer provide `gameVersionId` unless multiple versions need to be explicitly distinguished.

Modes:

- QUICK_START
- LEARN
- ASK_RULES

---


Design the domain carefully before writing endpoints.

At minimum, consider these concepts.

## Game

Fields should include concepts such as:

- id
- slug
- name
- publisher
- edition
- minPlayers
- maxPlayers
- estimatedDurationMinutes
- description
- active

Do not assume that two editions of a game always share identical rules.

---

## RuleDocument

Represents an imported source document.

Possible fields:

- id
- gameId
- name
- sourceType
- edition
- version
- language
- sourceReference
- importedAt

Examples of sourceType:

- RULEBOOK
- FAQ
- ERRATA
- EXPANSION

---

## RuleChunk

Represents a searchable portion of a rule document.

Possible data:

- id
- documentId
- gameId
- content
- sectionTitle
- pageNumber
- chunkIndex
- embedding
- metadata

Chunks must preserve enough source metadata to create citations.

---

## GameSession

Represents one table/customer game session.

Possible fields:

- id
- gameId
- edition
- playerCount
- mode
- status
- currentSetupStep
- createdAt
- endedAt

Modes:

- QUICK_START
- LEARN
- ASK_RULES

Status could include:

- ACTIVE
- COMPLETED
- CANCELLED

---

## ConversationMessage

Store session conversation history.

Fields may include:

- id
- sessionId
- role
- content
- timestamp

Roles:

- USER
- ASSISTANT
- SYSTEM

Do not blindly send the entire conversation history to the LLM forever.

Design a context strategy that can later support summarization or bounded history.

---

## SetupFlow

A game's setup must be represented as structured application data rather than one giant AI-generated paragraph.

A setup flow should contain ordered steps.

For example:

SETUP_STARTED

BOARD_CREATED

NUMBER_TOKENS_PLACED

PLAYER_COLORS_SELECTED

INITIAL_SETTLEMENTS_PLACED

GAME_READY

Each setup step should have:

- id or key
- order
- title
- instructions
- optional notes
- completion status for the session

The API must support:

- current setup step
- complete current step
- previous step
- next step
- restart setup

---

### ConversationMessage
Persist id, sessionId, role (USER/ASSISTANT/SYSTEM), content, and timestamp. Use bounded recent history with a future summarization boundary; never send unlimited history.

### SetupFlow and SetupStep
Bind setup flows to GameVersion. Store ordered steps with key, order, title, instructions, and optional notes. Persist per-session completion and current step. Support current, complete, next, previous, and restart. Teaching sections are game/version data, not shared Catan logic.

# 6. RAG DESIGN

RAG is one of the core engineering features of this project.

Do not implement:

user question
→ LLM

Use:

User Question
    ↓
Load Game Session
    ↓
Identify Game
    ↓
Resolve GameVersion / active ruleset
    ↓
Create query embedding
    ↓
Search relevant rule chunks
    ↓
Filter by GameVersion
    ↓
Filter by active expansions when applicable
    ↓
Rank relevant chunks
    ↓
Provide retrieved evidence to LLM
    ↓
Generate grounded answer
    ↓
Return answer with citations

The RAG implementation must be generic.

It must not contain logic such as:

if game == CATAN:
    ...

Shared retrieval code should work for any game.

Game-specific information should come from stored data and configuration.

Use PostgreSQL + pgvector.

The RAG application layer should expose abstractions such as:

RuleRetrievalService

or:

RuleKnowledgeService

Do not expose pgvector-specific implementation throughout the application.

---


RAG is one of the core engineering features of this project.

Do not implement it as:

"user question -> LLM"

Instead use:

User Question
    ↓
Determine current game/session
    ↓
Create query embedding
    ↓
Search relevant rule chunks
    ↓
Filter by game
    ↓
Filter by edition
    ↓
Filter by expansions if applicable
    ↓
Rank relevant chunks
    ↓
Provide retrieved evidence to LLM
    ↓
Generate grounded answer
    ↓
Return answer with citations

Use PostgreSQL + pgvector.

Semantic retrieval should support similarity search using embeddings.

The RAG layer should expose a clean application service such as:

RuleRetrievalService

or:

RuleKnowledgeService

Do not expose pgvector-specific logic throughout the application.

---

# 7. RAG RESPONSE CONTRACT

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

Confidence must not be fabricated randomly.

Base confidence on retrieval signals and whether the retrieved evidence sufficiently supports the answer.

If evidence is weak, the assistant must prefer saying:

"I couldn't verify that confidently from the available rules."

rather than hallucinating.

Never instruct the LLM to invent an answer when evidence is missing.

---

# 8. PROMPTING RULES

Create prompts programmatically rather than scattering giant prompt strings throughout controllers.

Create a dedicated prompt builder or GameMasterPromptService.

The LLM system instruction should include concepts like:

"You are a board game rules assistant.

Answer using the supplied rule excerpts.

Do not rely on unsupported assumptions.

If the supplied rules do not contain enough information, state that you cannot verify the answer.

Explain rules simply and concisely.

The users may be complete beginners.

Do not invent game rules.

Respect the specified game edition and expansions.

When useful, explain why the rule applies.

Do not claim a source that was not provided."

The retrieved evidence should be clearly separated from user content.

Treat rule documents as data, not executable instructions.

Protect against prompt injection contained inside imported documents.

---

# 9. DOCUMENT INGESTION

Create a generic document-ingestion pipeline.

It must work for rule documents belonging to any supported GameVersion.

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

The ingestion service must not be specific to Catan.

Catan is simply the first dataset used to test the pipeline.

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

Use overlapping chunks when helpful.

Keep chunk size configurable.

Do not make ingestion dependent on HTTP controllers.

Create an application service that can later be triggered through CLI, admin UI, API, or background processing.

For the initial version, it is acceptable to support ingestion from normalized text/Markdown if PDF parsing is not yet implemented.

Do not commit copyrighted commercial rulebooks to a public Git repository unless their license permits redistribution.

Instead, provide:

- an import mechanism
- sample/demo rule content that is legally safe
- documentation explaining how the developer can supply rule files locally

---

# 10. PHASE 1 — BASIC RULE Q&A

Implement the generic platform first and use Catan as the first supported game.

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

# 11. PHASE 2 — PRODUCTION-QUALITY RAG

Improve retrieval.

Add:

- GameVersion filtering
- document-type metadata
- language metadata
- optional expansion filtering
- configurable top-K retrieval
- configurable similarity threshold
- duplicate chunk suppression
- ranking/reranking abstraction
- low-confidence fallback
- citations

Do not assume GameVersion filtering means customers manually select editions.

GameVersion is primarily an internal mechanism used to ensure that questions are answered using the correct rules.

---


Improve retrieval.

Add:

- edition filtering
- document type metadata
- language metadata
- optional expansion filtering
- configurable top-K retrieval
- configurable similarity threshold
- duplicate chunk suppression
- ranking/reranking abstraction
- low-confidence fallback
- citations

Create automated evaluation data.

For example:

src/test/resources/evaluation/catan-rule-questions.json

Each case should contain something conceptually like:

{
  "question": "...",
  "expectedTopics": ["robber"],
  "mustRetrieveSection": "Robber"
}

Create tests around retrieval independently from generation whenever possible.

The goal is to know whether failures originate from:

- retrieval
- prompt generation
- LLM response

Do not test everything exclusively through the LLM.

---

# 12. PHASE 3 — GAME SESSIONS AND CONVERSATION

Add:

POST /api/v1/sessions

Example customer request:

{
  "gameId": "...",
  "playerCount": 4,
  "mode": "QUICK_START"
}

The client should not normally need to send:

gameVersionId

The Session creation service should:

1. load the selected Game
2. identify its default supported GameVersion
3. create the session using that GameVersion
4. store it so all later RAG requests use the same ruleset

Internally the resulting session may contain:

Game:
Catan

GameVersion:
5th Edition / English

Players:
4

Mode:
QUICK_START

The customer only needed to select:

Catan

Then add:

POST /api/v1/sessions/{sessionId}/messages

{
  "message": "How do I get resources?"
}

Followed by:

{
  "message": "What if the robber is there?"
}

The second question should use the existing session's:

- game
- resolved GameVersion
- player count
- mode
- conversation context

The version must remain stable throughout the session.

---


Add:

POST /api/v1/sessions

Example:

{
  "gameId": "...",
  "playerCount": 4,
  "mode": "QUICK_START"
}

Response returns session ID.

Add:

POST /api/v1/sessions/{sessionId}/messages

{
  "message": "How do I get resources?"
}

Then:

{
  "message": "What if the robber is there?"
}

The second question should be interpreted using conversation context.

Create a SessionContextService.

Responsibilities may include:

- game
- edition
- expansions
- player count
- selected mode
- recent conversation messages
- setup progress

Controllers should not assemble AI context themselves.

Persist conversation messages.

Apply a bounded-context strategy.

For example:

- recent N messages
- plus summarized prior context

You do not have to implement sophisticated summarization immediately, but design the code so it can be added without rewriting the system.

---

# 13. PHASE 3 — GAME MASTER MODES

Implement behavior for:

## ASK_RULES

Short, direct answers.

Example:

User:
"Can I trade when it isn't my turn?"

Expected behavior:
Answer the rule directly and cite the rule source.

---

## LEARN

Provide a structured explanation of the game.

The learning experience should be broken into logical sections, such as:

1. Goal
2. Components
3. Setup
4. Turn structure
5. Trading
6. Building
7. Special rules
8. Winning

Do not send an enormous response unless requested.

Allow the customer to progress through learning sections.

---

## QUICK_START

Explain only what players need to begin.

Defer situational rules until necessary.

For example:

"The robber becomes important when someone rolls a seven. I can explain it when that happens."

The system should be optimized to get players playing quickly.

---

# 14. PHASE 4 — GUIDED SETUP

The setup system must also be multi-game.

Do not hardcode Catan setup into controllers or shared workflow classes.

Prefer something like:

GameVersion
    ↓
SetupFlow
    ↓
SetupSteps

For example:

Catan GameVersion
→ Catan Setup Flow

Azul GameVersion
→ Azul Setup Flow

Splendor GameVersion
→ Splendor Setup Flow

The same setup engine should execute all of them.

Catan is the first implementation used to prove this design.

---


Implement deterministic setup workflows.

Do not rely on the LLM to remember what step the user is on.

Persist setup progress in application state.

Endpoints may include:

GET /api/v1/sessions/{id}/setup

POST /api/v1/sessions/{id}/setup/start

POST /api/v1/sessions/{id}/setup/next

POST /api/v1/sessions/{id}/setup/previous

POST /api/v1/sessions/{id}/setup/restart

Example response:

{
  "step": 3,
  "totalSteps": 8,
  "key": "PLAYER_COLORS",
  "title": "Choose player colors",
  "instruction": "Each player chooses one available color and takes the matching pieces."
}

The setup flow should be defined as game data/configuration or domain objects.

Avoid giant switch statements tied specifically to Catan.

The architecture must allow another board game to supply a different setup flow later.

---

# 15. PHASE 5 — VOICE

Voice must operate on top of the generic GameSession system.

The voice system must not know Catan-specific rules.

Flow:

Audio
    ↓
SpeechToTextClient
    ↓
Text
    ↓
GameSession
    ↓
Resolve game's stored ruleset
    ↓
Normal Game Master pipeline
    ↓
Text response
    ↓
TextToSpeechClient
    ↓
Audio

This ensures that when additional games are added later, the voice subsystem requires no game-specific changes.

---


After text interactions work properly, add voice.

Do not tightly couple the application to one speech vendor.

Create:

SpeechToTextClient

TextToSpeechClient

Voice interaction flow:

Audio
    ↓
SpeechToTextClient
    ↓
Text
    ↓
Game Master pipeline
    ↓
Answer text
    ↓
TextToSpeechClient
    ↓
Audio

Create an endpoint conceptually similar to:

POST /api/v1/sessions/{sessionId}/voice

Accept an audio file.

Return either:

- structured metadata + audio response

or:

- asynchronous-compatible response design if technically necessary

However, do not add message queues unless required.

For the MVP, assume push-to-talk interaction.

Do NOT implement always-listening microphones or wake-word detection yet.

Account for:

- invalid audio
- transcription errors
- unsupported file type
- AI provider failure
- timeout
- empty transcription

The text interaction API must remain fully usable without voice.

Voice is an additional interface, not the core domain.

---

# 16. API DESIGN

Use RESTful APIs.

Use DTOs.

Do not expose JPA entities directly.

Use Bean Validation.

Return consistent errors.

Example:

{
  "code": "GAME_NOT_FOUND",
  "message": "Game was not found",
  "timestamp": "...",
  "path": "/api/v1/..."
}

Create global exception handling.

Use proper HTTP status codes.

Document APIs using OpenAPI.

Keep endpoints versioned under:

/api/v1

---

# 17. DATABASE MIGRATIONS

Use Flyway.

All database changes must be migrations.

Do not rely on Hibernate automatic schema creation for production behavior.

Local development may validate the schema.

Prefer:

ddl-auto=validate

after migrations exist.

Add the pgvector extension through a migration or documented database initialization.

Create indexes appropriate for:

- foreign keys
- session lookup
- game slug
- rule document filtering
- vector similarity retrieval

Explain vector index choices in documentation.

---

# 18. SECURITY

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

Validate uploaded files.

Set reasonable upload limits.

Treat imported documents and user messages as untrusted input.

Prevent document text from overriding system prompts.

Do not expose stack traces to API consumers.

---

# 19. AI PROVIDER ABSTRACTION

Do not write business code such as:

openAiClient.chat(...)

inside controllers or domain services.

Use interfaces.

Example:

public interface LlmClient {
    LlmResponse generate(LlmRequest request);
}

public interface EmbeddingClient {
    Embedding generate(String text);
}

Implement provider adapters separately.

The application should theoretically be able to replace one provider with another without rewriting the Game Master domain logic.

Same for:

SpeechToTextClient
TextToSpeechClient

---

# 20. CONFIGURATION

Externalize configuration.

Examples:

boardgame.rag.top-k
boardgame.rag.similarity-threshold
boardgame.rag.chunk-size
boardgame.rag.chunk-overlap
boardgame.ai.model
boardgame.ai.temperature

For rule answers, use low randomness.

Do not bury important constants in application code.

---

# 21. OBSERVABILITY

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

# 22. TESTING REQUIREMENTS

Testing is mandatory.

Create:

## Unit tests

For:

- setup workflow
- confidence calculation
- prompt building
- chunking
- domain validation
- session state transitions

## Repository tests

Use Testcontainers with PostgreSQL + pgvector.

Test vector retrieval against known embeddings where practical.

## Integration tests

Test:

- create game
- create/import document
- create session
- ask question
- guided setup progression

Mock external LLM/speech APIs in ordinary integration tests.

Do not make the default test suite require paid AI calls.

Create optional manual/AI evaluation tests separately.

---

# 23. ERROR HANDLING

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

An AI provider outage should not cause a cryptic HTTP 500.

Return meaningful application errors.

---

# 24. RESILIENCE

External APIs are unreliable.

Add sensible:

- timeouts
- retry policy where safe
- graceful error handling

Do not automatically retry requests in ways that can cause duplicate side effects.

For AI requests, a small bounded retry may be acceptable for transient failures.

Do not create complicated resilience infrastructure unless justified.

---

# 25. README

The README must make the architecture clear:

Board Game Master is a multi-game platform.

Catan is currently the first/reference supported game.

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
6. Define learning content if required
7. Add retrieval evaluation cases
8. Publish/activate the game


The README must also document the problem, planned versus implemented features, architecture diagram, RAG flow, stack, local setup, environment variables, database startup, tests, API examples, phases, and deferred future work. Do not present planned commands or features as already operational.

# 26. SCOPE AND NON-GOALS

Implement Phases 1–5 only. Physical robot hardware, computer vision, café inventory, recommendations, deterministic board-state validation, analytics, always-listening microphones, and wake-word detection are deferred. No microservices, Kubernetes, event buses, Redis, or queues without a demonstrated need.

# 27. DEVELOPMENT PROCESS

Work strictly one milestone at a time. Before implementation, inspect existing code, read this specification and docs/DEVELOPMENT_STATUS.md, summarize the intended scope, and identify validation. Implement only the requested milestone. Resolve relevant test failures before declaring it complete. Report changes, validation, limitations, and manual demonstration steps. Update development status honestly. Stop after the current milestone; do not automatically begin the next one. Do not claim tests passed if they were not run.

# 28. IMPLEMENTATION ORDER

## Milestone 0 — Project Bootstrap
Create the Java 21+ Spring Boot Gradle project and wrapper, feature-oriented packages, configuration profiles, Docker Compose PostgreSQL + pgvector service, Flyway initialization, schema validation, health endpoint, OpenAPI configuration, consistent API error foundation, and basic startup tests. Document setup and environment configuration. No game-specific functionality.
Acceptance: local database starts, migrations run, application starts, health/API documentation are reachable, and bootstrap tests pass without paid AI calls.

## Milestone 1 — Game Domain
Implement generic Game and GameVersion entities, repositories, services, REST APIs, validation, migrations, and tests. Create Catan as data with one supported default version. Validate version ownership and default resolution.
Acceptance: games and versions can be created/read; invalid players/configuration fail clearly; customers can resolve a default version; no Catan-specific Java services or entities.

## Milestone 2 — Rule Documents
Implement version-scoped RuleDocument and RuleChunk persistence, permitted local text/Markdown import, cleanup, configurable semantic chunking, source metadata, validation, and ingestion tests. PDF extraction is optional.
Acceptance: imported content yields ordered traceable chunks belonging to the correct version; malformed input fails clearly; no copyrighted rulebooks are committed.

## Milestone 3 — Embeddings and Vector Search
Implement EmbeddingClient and its adapter, embedding storage, pgvector retrieval behind RuleRetrievalService, version-scoped search, configuration, migrations/indexes, and Testcontainers tests.
Acceptance: known embeddings retrieve expected chunks; other games/versions are excluded; provider failures are meaningful.

## Milestone 4 — RAG Question Answering
Implement LlmClient and adapter, prompt builder, question endpoint with default-version resolution, grounded generation, source references, evidence-based confidence, and insufficient-evidence fallback.
Acceptance: Phase 1 works end to end with permitted Catan input; unsupported answers are not invented; default tests use provider doubles.

## Milestone 5 — RAG Improvements
Implement Phase 2 metadata filtering, configurable top-K/thresholds, duplicate suppression, ranking/reranking boundary, evaluation fixtures, and retrieval diagnostics.
Acceptance: evaluate retrieval separately from generation; ruleset isolation and weak-evidence fallback are verified; document evaluation results.

## Milestone 6 — Game Sessions
Implement Phase 3 sessions, stable resolved GameVersion, conversation persistence, bounded context, SessionContextService, and ASK_RULES/LEARN/QUICK_START behavior with configurable teaching content.
Acceptance: customers create a session with gameId/playerCount/mode only; follow-ups use context; default-version changes do not silently change an existing session's ruleset.

## Milestone 7 — Guided Setup
Implement Phase 4 version-bound SetupFlow and ordered steps, persisted progress, start/current/complete/next/previous/restart operations, and deterministic transition tests.
Acceptance: the generic engine executes Catan's configured flow, persists progress, and handles boundaries/restart correctly without LLM-owned state.

## Milestone 8 — Voice
Implement Phase 5 SpeechToTextClient/TextToSpeechClient adapters and push-to-talk endpoint using the existing session/text pipeline. Validate audio, timeouts, provider failures, and empty transcription.
Acceptance: audio input produces a grounded text answer and speech output; ordinary tests mock speech providers; text remains usable without voice.

Phase mapping: Phase 1 = Milestones 1–4 (after bootstrap); Phase 2 = Milestone 5; Phase 3 = Milestone 6; Phase 4 = Milestone 7; Phase 5 = Milestone 8.

# 29. ACCEPTANCE CRITERIA

The project is considered complete through Phase 5 when this scenario works:

1. Developer starts PostgreSQL with Docker Compose.
2. Application starts successfully.
3. The generic Game and GameVersion architecture exists.
4. Catan is registered as the first supported Game.
5. Catan has one configured default GameVersion.
6. A permitted/local Catan rule source can be ingested for that GameVersion.
7. Rule chunks receive embeddings.
8. User creates a session using:

{
  "gameId": "<catan-id>",
  "playerCount": 4,
  "mode": "QUICK_START"
}

9. User does not need to manually provide an edition.
10. The backend resolves the appropriate Catan GameVersion.
11. User asks:

"What happens if I roll a seven?"

12. Relevant chunks from the resolved rule set are retrieved.
13. The LLM answers using those chunks.
14. The response contains source references.
15. User asks:

"What if I don't have any cards?"

16. The assistant interprets the question using session conversation context.
17. User starts guided setup.
18. The generic setup engine loads the Catan setup flow.
19. The API walks the user through setup step by step.
20. User submits a question through voice.
21. Audio is transcribed.
22. The same Game Master pipeline processes the text.
23. The resulting answer is converted to speech.
24. Automated tests pass.
25. No secrets are committed.
26. README explains how to run and demonstrate the system.
27. The core architecture contains no unnecessary dependency on Catan.
28. Adding another board game does not require rewriting the RAG, session, voice, or setup engines.


# 30. COMPLETION AND VALIDATION

A milestone is complete only when its implemented behavior satisfies its acceptance criteria and relevant tests pass. Use unit tests, PostgreSQL + pgvector Testcontainers tests, and Spring Boot integration tests as appropriate. Default tests require no paid provider calls. Keep optional real-provider evaluation separate. Document any unverified behavior rather than marking it complete.

# 31. ADDING A SECOND GAME

Register Game and supported GameVersion metadata; import permitted documents; generate embeddings; configure version-bound setup and learning content; add evaluation/test fixtures; activate the supported version. Core ingestion, RAG, sessions, setup execution, and voice must be reusable without redesign. Expansion support must remain possible without implementing complex expansion behavior now.

# 32. FIRST IMPLEMENTATION TASK

The repository skeleton does not complete Milestone 0. When implementation is explicitly requested, begin Milestone 0 only. Do not begin Milestone 1 automatically. Build for many games. Validate with one game.


# Board Game Master — Codex Instructions

## Project

Board Game Master is a multi-game AI board game assistant.

The platform teaches customers how to play a selected board game, guides them through physical setup, and then remains available during gameplay to answer rule questions.

Catan is the first reference implementation.

Catan must not be hardcoded into shared infrastructure.

## Authoritative Specification

The authoritative product and architecture specification is:

`PROJECT_SPEC.md`

Use it when:

- implementing milestones
- changing architecture
- changing domain models
- changing TeachingFlow
- changing SetupFlow
- changing GameSession behavior
- changing RAG
- changing AI integrations
- changing voice functionality
- changing the demo UI

Do not intentionally contradict `PROJECT_SPEC.md` without explicitly explaining why.

## Development Status

Current progress is tracked in:

`docs/DEVELOPMENT_STATUS.md`

Update that file when:

- a milestone is completed
- the active milestone changes
- a major architectural decision changes implementation status

## Core Product Flow

The default customer experience is teaching-first:

Select Game

→

Create Session

→

Teach Game

→

Guided Setup

→

Finish Teaching

→

Ready to Play

→

Playing / Rule Q&A

The product must not behave primarily as an open chatbot.

Rule Q&A is available during both teaching and gameplay.

Questions during teaching must not destroy or reset teaching progress.

Use QUICK / STANDARD / DETAILED teaching depth, with STANDARD as the default.
The backend owns persisted TEACHING / SETUP / PLAYING / COMPLETED stages and lesson/setup progress.
Milestone 6 adds sessions and TeachingFlow; Milestone 7 integrates SetupFlow; Milestone 8
adds voice and a thin Spring Boot/Thymeleaf browser demo. These requirements do not authorize
starting an unrequested milestone.

## Architecture Rules

- Build for multiple games.
- Validate first with Catan.
- Keep TeachingFlow generic.
- Keep SetupFlow generic.
- Keep RAG generic.
- Keep session logic generic.
- Keep voice integrations game-independent.
- Keep UI thin.
- Persist deterministic workflow state in the backend.
- Do not make the LLM responsible for workflow state.
- Do not duplicate business logic in frontend JavaScript.

## Development Rules

- Work milestone by milestone.
- Do not implement future milestones prematurely.
- Inspect existing code before changing architecture.
- Use Java 21+.
- Use Spring Boot.
- Use PostgreSQL + pgvector.
- Use Flyway migrations.
- Keep external AI providers behind interfaces.
- Do not expose JPA entities directly through REST endpoints.
- Never commit secrets.
- Add/update tests when behavior changes.
- Run relevant tests before considering a milestone complete.
- Do not silently ignore failing tests.

## Current Milestone

Determine the current milestone from:

`docs/DEVELOPMENT_STATUS.md`

Do not automatically begin the next milestone after finishing the current one unless explicitly requested.

## Repository Safeguards

- Use Gradle unless the repository already uses Maven.
- Keep provider SDKs in infrastructure adapters, outside domain code.
- Use DTO validation and consistent API errors.
- Never commit unlicensed commercial rulebooks or secrets.
- Preserve existing work and accurate milestone/test history.
- Do not mark unfinished work complete or claim checks passed when they were not run.
- A documentation-only request authorizes documentation edits only.

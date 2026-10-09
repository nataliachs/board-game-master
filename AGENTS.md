# Working on Board Game Master

These are the repository instructions for coding agents. Product requirements live in
[PROJECT_SPEC.md](PROJECT_SPEC.md); implementation progress and test history live in
[docs/DEVELOPMENT_STATUS.md](docs/DEVELOPMENT_STATUS.md).

## Project

Board Game Master teaches a selected board game, guides physical setup, and answers rule
questions during play. It supports multiple games. Catan is the reference dataset, so its
rules belong in data and fixtures rather than shared Java services. Validate the initial
reference behavior with Catan.

The main flow is:

Select game → Create session → Teach → Guided setup → Resume teaching → Ready to play → Play → Complete.

Teaching is the default experience; the product should not open as a general chatbot.
Questions can interrupt teaching or setup without resetting either. STANDARD is the default
teaching depth; QUICK and DETAILED use the same session workflow. The backend stores the
TEACHING, SETUP, PLAYING and COMPLETED stages and all lesson/setup progress.

## Before making changes

- Read the specification and development status, then inspect the existing implementation.
- Determine the active milestone from DEVELOPMENT_STATUS.md and follow the requested scope.
- Read the specification before changing architecture, domain models, RAG, AI integrations,
  teaching, setup, sessions, voice or the UI.
- Explain any necessary departure from the specification rather than silently contradicting it.
- Preserve existing work. A documentation-only request authorizes documentation edits only.
- Completing a milestone does not authorize starting the next one.

## Architecture

- Use a modular monolith with feature-oriented packages.
- Use Java 21+, Spring Boot, PostgreSQL with pgvector, and Flyway migrations.
- Use Gradle unless the repository already uses Maven.
- Keep ingestion, retrieval, teaching, setup, sessions and voice reusable across games.
- Keep AI providers behind interfaces and provider SDKs in infrastructure adapters.
- Keep domain logic independent of provider SDKs and return DTOs rather than JPA entities.
- Validate DTOs and use consistent API errors.
- Store workflow state in the backend. The LLM explains rules; it does not choose the next
  lesson, remember setup progress or change session state.
- Keep the Thymeleaf UI and its JavaScript focused on presentation and browser interaction.
  Reuse backend services rather than duplicating business logic.

## Milestone boundaries

Milestone 6 covers sessions and teaching. Milestone 7 integrates guided setup. Milestone 8
adds voice and the Spring Boot/Thymeleaf demo. Check the status file for what is implemented;
these boundaries do not authorize unrequested work.

## Validation and repository care

- Add or update tests when behavior changes and run the relevant checks.
- Report failures and unverified behavior. Only claim checks that actually ran and passed.
- Update DEVELOPMENT_STATUS.md when implementation status, the active milestone or a major
  architectural decision changes. Retain useful test history and known limitations.
- Never commit secrets or unlicensed commercial rulebooks.
- Follow any task-specific restrictions on paid calls, database changes, commits and pushes.

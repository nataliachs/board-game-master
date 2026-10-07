# Board Game Master — Agent Instructions

- Read PROJECT_SPEC.md for milestone implementation, architecture, domain, RAG, sessions, setup, AI integrations, or voice changes.
- Read docs/DEVELOPMENT_STATUS.md and inspect existing code before implementation.
- Work only on the explicitly requested milestone. Do not start the next milestone automatically.
- Keep infrastructure game-independent. Catan is reference data; separate Game from GameVersion.
- Use Java 21+, Spring Boot, PostgreSQL + pgvector, Flyway, and Gradle unless the repository already uses Maven.
- Keep LLM, embedding, STT, and TTS providers behind interfaces; keep SDKs out of domain code.
- Use DTOs, validation, consistent errors, and migrations; never expose JPA entities directly.
- Never commit secrets or unlicensed rulebooks.
- Add meaningful tests for behavior changes and run relevant checks. Report checks that could not run.
- Update development status when milestones complete or material decisions change. Do not mark unfinished work complete.


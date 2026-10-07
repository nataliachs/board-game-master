# Bootstrap architecture

Root package: `com.boardgamemaster`. The application scans feature packages beneath it.
Milestone 0 contains only `common.config` and `common.exception` infrastructure.

Future feature packages are reserved below; they contain documentation only:
`game`, `rules`, `rag`, `session`, `gamemaster`, `setup`, and `ai`.
Add domain/application/infrastructure/api subpackages only as each milestone needs them.
No game entities, tables, seed data, repositories, provider interfaces, or game endpoints exist yet.

HTTP failures share `code`, `message`, UTC `timestamp`, and request `path`.
MVC advice preserves HTTP status and framework headers; servlet dispatches use the same DTO.
Unexpected exception messages and stack traces are not sent to clients or logged.
Each request gets a generated `X-Request-ID`, also placed in the logging MDC.
Console logs use Spring Boot ECS JSON; database health details remain private.

Flyway owns schema changes; Hibernate uses `ddl-auto=validate` and open-in-view is disabled.
V1 installs pgvector only. There are no application tables for Hibernate to validate yet.
The database account needs extension-creation privileges for V1; production provisioning may
install the extension ahead of time. Production credentials come from environment variables.

The local profile imports optional `.env` as Java properties. Use simple `NAME=value` lines
without shell `export`, quotes, or Compose-only interpolation. Run from the repository root.
The production profile does not import `.env` and disables Swagger/OpenAPI by default.
This bootstrap has no authentication and is intended for local development.

Tests split fast MVC/error checks (`test`) from real PostgreSQL/pgvector startup and HTTP
checks (`integrationTest`). `check` requires both, so unavailable Docker cannot yield a
successful full verification. Neither suite calls paid services.

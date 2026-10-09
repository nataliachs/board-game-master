# Recording a short demo

Aim for two or three minutes. Show the working application first, then explain a few decisions
in the code. The [README quick start](../README.md#local-setup) runs teaching and setup without
paid providers. Use an original or permitted dataset when demonstrating rule questions.

## Browser walkthrough

1. **Choose a game.** Show the player count and teaching depth. Explain that different games
   share the same application services; game-specific content is stored in the database.
2. **Start learning.** Read a lesson, use Previous or Repeat, and point out the learning path.
3. **Prepare the table.** Enter guided setup, confirm a step, then return to teaching. Explain
   that the backend saves the position; confirming a step does not inspect the physical board.
4. **Start playing.** Finish teaching and show that the same session remains available for
   review and questions. End the session and show that its history remains readable.

## Optional cited-answer segment

Record this only after importing and indexing permitted rules and enabling the required
providers. These actions can incur charges. Leave speech disabled unless demonstrating it.
Use a prepared Lantern Trail session with the original sample rules rather than a commercial
rulebook. Ask one supported question, expand its source, then ask an unsupported question:

- “What happens when my token reaches the last space?” The sample supports taking one spare
  marker and returning the token to the first space. Check that the displayed quote supports it.
- “Can I buy a teleport card?” The sample has no such rule; expect the insufficient-evidence
  fallback rather than a made-up answer.

Show the actual results. If a case fails, keep it as an investigation example rather than
presenting an edited recording as evidence of reliable behavior. This short demonstration
does not replace broader retrieval, answer-quality or speech evaluation.

## Explain the implementation

Pick two or three details you can explain in your own words:

- Why a session pins its game version instead of following a changing catalog default.
- Why application services own lesson/setup progress rather than the LLM.
- What citation validation checks, and what it cannot prove about an answer.
- How mock providers and isolated PostgreSQL tests make checks repeatable without API keys.

Before sharing, verify the GitHub Actions run for the exact commit being shown. The badge in
the README tracks `main`; local changes are not covered until a corresponding run executes.
The dated [development status](DEVELOPMENT_STATUS.md) separates automated checks, manual
observations and remaining limitations.

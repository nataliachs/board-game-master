# Recording a short demo

Aim for two or three minutes: show the working application first, then explain two or three
decisions in the code. The [README quick start](../README.md#run-it-locally) runs lessons and
setup without any AI provider. Cited answers and PDF import need an OpenAI key and can incur
small charges.

## Browser walkthrough (no AI needed)

1. **Choose a game.** Show the library's search and filters, then pick Ticket to Ride, four
   players and Standard. Mention that every game uses the same services; game-specific content
   is data in the database.
2. **Learn.** Read a lesson, use Previous or Repeat, and point out the learning path.
3. **Set up the table.** At "Prepare the table", continue into guided setup, show a step with a
   warning, finish setup and land back on the saved lesson. The backend stores the position;
   confirming a step doesn't inspect the physical board.
4. **Play.** Finish the lessons and show that the same session stays open for review and
   questions. End the game and show that its history is still readable.

## Cited answers (needs an OpenAI key)

Use a game whose rules are uploaded and indexed: a PDF you imported, or a rules summary from
[`docs/rules/`](rules/) uploaded in the admin area. Ask one question the rules answer, open
**View sources** and show the quote and page, then ask one they don't, such as "What's the best
strategy?", and show the honest fallback instead of a made-up answer.

## Import a game from a PDF (needs an OpenAI key)

In `/admin → Add a game`, import a rulebook PDF you own. Show the summary message, then the
drafted setup steps and lessons, and explain that the game stays hidden until it has been
reviewed. This takes up to three minutes, so record it separately or cut the wait.

Show the real results. If a case fails, keep it as an investigation example rather than
presenting an edited recording as evidence of reliable behaviour.

## Explaining the implementation

Pick two or three details you can explain in your own words:

- Why a session pins its game version instead of following a changing catalog default.
- Why the backend, not the LLM, owns lesson and setup progress.
- What quote validation checks, and what it can't prove about an answer.
- Why AI-drafted lessons are saved as reviewed data instead of being generated per session.
- How stub providers and throwaway PostgreSQL containers make the tests repeatable without API keys.

Before sharing, check that the GitHub Actions run for the commit you're showing passed. The
[development status](DEVELOPMENT_STATUS.md) separates automated checks, manual observations and
remaining limitations.

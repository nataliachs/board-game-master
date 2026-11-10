# UI guide

This guide describes the browser experience for Board Game Master. Product behavior and
technical boundaries remain in [PROJECT_SPEC.md](../PROJECT_SPEC.md). The UI is a thin
Thymeleaf layer over the existing application services.

## Goal and journey

Help a player choose a game, learn it one lesson at a time, set up the physical table, and
ask rule questions during play without losing their place. The journey is **Choose game →
Teach → Guided setup → Resume teaching → Play → Complete**. Teaching is the starting point;
the interface should not open as a general chat.

## Screen priorities

| Screen | Most important information | Primary action |
| --- | --- | --- |
| Home | Available game, player count, teaching depth | Start game |
| Teaching | Current lesson, progress, explanation | Continue lesson or start setup |
| Setup | Current physical step, progress, warnings | Confirm step and continue |
| Playing | Question input, latest answer and its sources | Ask a question |
| Review | Lessons and setup steps already reached | Return to session |

Questions may interrupt teaching or setup. After an answer, the current step and its next
action must remain easy to find. Source quotes must be visibly distinct from authored
lessons. Low-confidence answers must explain that the available rules were insufficient.

## Visual and interaction rules

- Use the "Daylight café" palette in `demo.css`: warm cream background, espresso header,
  terracotta primary actions, honey accents and teal for success states.
- The home page is the one exception: it uses the darker "café at night" look in `home.css`
  (animated café scene, 3D shelf of game boxes, scroll track, ask-the-host chat) with
  self-hosted fonts in `static/fonts`. Session, review and admin pages stay on `demo.css` so
  lessons and setup steps remain easy to read at the table.
- Home page motion must respect `prefers-reduced-motion`, canvas loops run only while on
  screen, and the page must keep working without JavaScript (plain game list and start form).
- The home page chat asks about the game pulled from the shelf and sends nothing until the
  visitor asks, because each question is a paid request.
- Give each screen one prominent next action. Put secondary actions nearby but visually quieter.
- Show stage and step progress in text as well as visually. Do not rely on color alone.
- Keep lessons and instructions readable at a table on a phone: comfortable line length,
  generous tap targets, and no horizontal page scrolling.
- Make the learning path compact on narrow screens while keeping the current step visible.
- Keep the conversation in a fixed-height box that scrolls on its own and opens at the newest
  message, so long sessions do not stretch the page. "Older messages" sits above the box.
- Keep voice controls optional and text interaction usable when voice or AI providers are off.
- Use clear labels and messages for empty data, invalid input, missing sessions, unavailable
  providers, insufficient rule evidence, and speech failures.

## Acceptance check for UI changes

Review home, teaching, setup, playing and review at phone and desktop widths. Confirm keyboard
focus is visible, headings and labels are meaningful, long text wraps, and the primary action
stays clear. Verify that the browser still uses backend-owned session progress and that asking
a question does not advance or reset the lesson or setup step.

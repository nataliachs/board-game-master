# Admin guide: adding a game

The admin area lets you add games without writing SQL or calling the API. Open it at
[localhost:8080/admin](http://localhost:8080/admin), or choose **Admin** in the page header.

> The admin area has no sign-in, like the rest of the demo. Run the app only on your own
> computer, and don't expose it to the internet.

## Before you start

Start the database and the app as described in the README's *Local setup*:

```powershell
docker compose up -d --wait
.\gradlew.bat bootRun
```

Lessons and setup need no AI keys. Rule questions do (see step 4).

## 1. Create the game

1. Go to **Admin → Add a game**.
2. Fill in the name, player range, play time, edition and language code (`en`, `id`, `en-US`…).
   Category, publisher, description and cover are optional.
3. Choose **Save and continue**. The app creates the game and a default version, and opens its
   editor.

The web address name (slug) is made from the game name, like `ticket-to-ride`. It can't be
changed later.

## 2. Add setup steps

Setup steps are what players do with the real box before they play.

1. In **2. Setup steps**, fill one card per step: a **title** and an **instruction**.
2. Optional: **notes** and **warnings**, one per line. Warnings are shown in a highlighted box.
3. **Order** controls the sequence. **Key** is filled in from the title if you leave it empty.
4. Choose **Save setup steps**. Empty cards are ignored, and five new blank cards appear after
   each save, so you can keep adding steps.

## 3. Add lessons

1. In **3. Lessons**, fill one card per lesson: a **title** and an **explanation**.
2. Under **Shown in**, tick the teaching styles that include the lesson:
   - Tick all three for core lessons.
   - Leave **Quick** unticked for detail that new players can skip.
   - Tick only **Detailed** for edge cases.
3. Pick **Starts setup** on the one lesson where players should stop and set up the table
   (usually right after the goal). It must be shown in all three styles, and step 2 must be saved
   first. Choose **No lesson starts setup** if the game needs no guided setup.
4. Optional: open **Examples, tips and references** to add extra lines for that lesson.
5. Choose **Save lessons**.

Every teaching style needs at least one lesson. If something is missing, the page tells you
what to fix and keeps everything you typed.

Once the game is active and has lessons, it shows on the home page with the status **Ready**.

## 4. Optional: rules for questions

The Game Master can answer rule questions with citations once rules are uploaded and indexed.

1. Prepare the rules as a UTF-8 `.txt` or `.md` file. Only use rules you have permission to use,
   and keep commercial rulebooks out of Git (for example in the ignored `local-rules/` folder).
2. In **4. Rules for questions**, choose the file, a type (Rulebook, FAQ, Errata or Expansion) and
   **Upload rules**.
3. Choose **Index for questions**. This needs an embedding provider in your private `.env`
   (see the README) and may cost money. If it is disabled, the page says so and nothing changes.

## Editing later

- **Details** (name, players, category, cover, description) save straight away. Untick **Show
  this game to players** to hide a game without deleting it.
- **Saving lessons or setup steps publishes a new set.** New sessions use the latest set; sessions
  that already started keep the lessons and steps they began with.
- There is no delete button. Hide a game instead.

## Cover images

1. Put the image in `src/main/resources/static/images/covers/`, e.g. `my-game.jpg`.
2. In **1. Game details**, set **Cover image** to `/images/covers/my-game.jpg` and save.
3. Restart the app so it picks up the new file.

An `https://` image address also works. Games without a cover show their initials.

## Keeping a game permanently

Games added in the admin area live in your local database. Running `docker compose down -v`
deletes them. To ship a game with the project, add it as a Flyway migration instead (copy the
pattern in `V9__replace_reference_game_with_ticket_to_ride.sql` into a new `V10__…` file).

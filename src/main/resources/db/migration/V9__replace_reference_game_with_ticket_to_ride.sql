-- Replace the built-in Catan reference game with Ticket to Ride (2025 edition).
--
-- 1. Remove the Catan seed from V3, V6 and V7, together with anything attached to its
--    version: sessions, conversation history, imported rule documents, chunks and vectors.
--    Games registered through the API are left untouched.
-- 2. Add Ticket to Ride with an introductory teaching flow and a guided setup flow.
--    The lesson and setup text is original, concise beginner guidance written for this
--    project and checked against the Ticket to Ride rules booklet (Days of Wonder, 2025).
--    It does not reproduce the booklet. No rulebook is bundled or indexed.

-- 1. Remove Catan -----------------------------------------------------------------------
DELETE FROM conversation_messages WHERE session_id IN (
    SELECT s.id FROM game_sessions s JOIN game_versions v ON v.id = s.game_version_id
    WHERE v.game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM game_sessions WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM rule_chunk_embeddings WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM rule_chunks WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM rule_documents WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM setup_steps WHERE setup_flow_id IN (
    SELECT f.id FROM setup_flows f JOIN game_versions v ON v.id = f.game_version_id
    WHERE v.game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM setup_flows WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM teaching_sections WHERE teaching_flow_id IN (
    SELECT f.id FROM teaching_flows f JOIN game_versions v ON v.id = f.game_version_id
    WHERE v.game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM teaching_flows WHERE game_version_id IN (
    SELECT id FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001');
DELETE FROM game_versions WHERE game_id = '00000000-0000-0000-0000-000000000001';
DELETE FROM games WHERE id = '00000000-0000-0000-0000-000000000001';

-- 2. Add Ticket to Ride ----------------------------------------------------------------
INSERT INTO games (id, slug, name, publisher, min_players, max_players, estimated_duration_minutes,
    description, active, category, cover_image_url)
VALUES ('00000000-0000-0000-0000-000000000101', 'ticket-to-ride', 'Ticket to Ride', 'Days of Wonder', 2, 5, 60,
    'Collect train cards and claim railway routes to connect cities across North America.', TRUE, 'Family', NULL);

INSERT INTO game_versions (id, game_id, edition, version_label, language, release_year, active, default_version)
VALUES ('00000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000101',
    '2025 Edition', 'base-game', 'en', 2025, TRUE, TRUE);

INSERT INTO setup_flows (id, game_version_id, name, created_at)
VALUES ('00000000-0000-0000-0000-000000000401', '00000000-0000-0000-0000-000000000201',
    'Ticket to Ride guided setup', '2026-11-07T00:00:00Z');

INSERT INTO setup_steps (id, setup_flow_id, step_key, step_order, title, instruction, notes, warnings)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000401'::uuid, step_key, step_order, title, instruction,
    notes::jsonb, warnings::jsonb
FROM (VALUES
    ('BOARD', 0, 'Place the board',
     'Put the map of North America in the middle of the table, where everyone can reach it.',
     '[]', '[]'),
    ('TRAINS', 1, 'Take trains and a scoring marker',
     'Each player chooses a color, counts out 45 plastic trains of that color and takes the matching scoring marker. Put every scoring marker on the 0 space of the track around the board.',
     '[]', '["The box holds a few spare trains in each color, so count exactly 45 per player."]'),
    ('TRAIN_CARDS', 2, 'Deal train cards',
     'Shuffle the train cards and deal 4 to each player as a starting hand.',
     '[]', '[]'),
    ('FACE_UP_CARDS', 3, 'Turn up five cards',
     'Put the rest of the train deck beside the board and turn its top 5 cards face up next to it.',
     '[]', '["If 3 or more of the 5 face-up cards are locomotives, discard all 5 and turn up 5 new cards."]'),
    ('BONUS_CARD', 4, 'Set out the bonus card',
     'Place the longest path bonus card face up beside the board.',
     '[]', '[]'),
    ('TICKETS', 5, 'Choose starting tickets',
     'Shuffle the destination tickets and deal 4 to each player. Everyone secretly keeps at least 2 and may keep more. Shuffle the returned tickets together, put them under the ticket deck and place the deck beside the board.',
     '["Keep your tickets hidden until the end of the game."]', '[]'),
    ('FINAL_CHECK', 6, 'Confirm the table is ready',
     'Check that everyone has 45 trains, a marker on 0, 4 train cards and their tickets. Pick a first player however you like; play then goes clockwise. Complete setup to return to your lesson.',
     '["Completion records your confirmation; the application cannot inspect the physical table."]', '[]')
) AS steps(step_key, step_order, title, instruction, notes, warnings);

INSERT INTO teaching_flows (id, game_version_id, name, created_at)
VALUES ('00000000-0000-0000-0000-000000000301', '00000000-0000-0000-0000-000000000201',
    'Ticket to Ride introductory lessons', '2026-11-07T00:00:00Z');

INSERT INTO teaching_sections (id, teaching_flow_id, section_key, section_order, title, explanation,
    depths, examples, tips, rule_references, setup_trigger)
SELECT gen_random_uuid(), '00000000-0000-0000-0000-000000000301'::uuid, section_key, section_order, title, explanation,
    depths::jsonb, examples::jsonb, tips::jsonb, rule_references::jsonb, setup_trigger
FROM (VALUES
    ('INTRODUCTION', 0, 'Welcome to Ticket to Ride',
     'This is a cross-country train game set on a map of North America. You collect colored train cards and spend them to claim railway routes between cities. We will learn one topic at a time.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '[]', '["Introduction"]', FALSE),
    ('OBJECTIVE', 1, 'Your goal',
     'Finish with the most points. You score by claiming routes, by completing the destination tickets you keep, and by building the longest continuous path. Tickets you keep but do not complete cost you points.',
     '["QUICK","STANDARD","DETAILED"]',
     '["A ticket is complete when an unbroken line of your own trains connects both cities printed on it."]', '[]',
     '["Object of the Game"]', FALSE),
    ('PHYSICAL_SETUP', 2, 'Prepare the table',
     'Choose Continue to start guided setup. When the table is ready, return here for the gameplay lessons.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '[]', '["Setting up the Game"]', TRUE),
    ('TURN', 3, 'Your turn: one action',
     'Turns go clockwise. On your turn you do exactly one of three things: draw train cards, claim one route, or draw destination tickets.',
     '["QUICK","STANDARD","DETAILED"]', '[]',
     '["You will usually draw cards for a few turns before you have enough to claim a long route."]', '["The Game Turn"]', FALSE),
    ('DRAW_CARDS', 4, 'Drawing train cards',
     'Take 2 train cards. Each one can come from the 5 face-up cards or from the top of the deck. When you take a face-up card, refill that spot from the deck straight away. There is no hand limit.',
     '["QUICK","STANDARD","DETAILED"]', '[]',
     '["When the deck runs out, shuffle the discarded train cards into a new deck."]', '["Draw Train Cards"]', FALSE),
    ('LOCOMOTIVES', 5, 'Locomotives are wild',
     'Locomotives count as any color when you claim a route. Taking a face-up locomotive must be your first draw of the turn, and then you take no second card. A locomotive drawn blind from the deck counts as a normal single card. If 3 face-up cards are ever locomotives, discard all 5 and turn up 5 new ones.',
     '["STANDARD","DETAILED"]', '[]', '[]', '["Locomotives"]', FALSE),
    ('CLAIM_ROUTE', 6, 'Claiming a route',
     'A route is the row of spaces between two neighboring cities. Play as many cards as it has spaces, all matching its color, then put one of your trains on each space and discard the cards. A gray route takes a set of any one color. Claim the whole route at once; it does not need to connect to your other routes.',
     '["QUICK","STANDARD","DETAILED"]',
     '["Route points: 1 space scores 1, 2 spaces 2, 3 spaces 4, 4 spaces 7, 5 spaces 10, 6 spaces 15. Move your scoring marker right away."]',
     '[]', '["Claim 1 Route"]', FALSE),
    ('DOUBLE_ROUTES', 7, 'Double routes',
     'Some cities are joined by two parallel routes. One player can never own both. In games with 2 or 3 players, only one of the pair can be used at all: once it is claimed, the other is closed.',
     '["STANDARD","DETAILED"]', '[]', '[]', '["Double-routes"]', FALSE),
    ('TICKETS', 8, 'Drawing destination tickets',
     'Draw 3 tickets from the deck (or all that remain if there are fewer) and keep at least 1. Return the others to the bottom of the deck. You can only discard tickets you just drew, never ones already in your hand. Keep them secret until the end.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '[]', '["Draw Tickets"]', FALSE),
    ('GAME_END', 9, 'How the game ends',
     'When someone finishes a turn with 2 or fewer trains left in their supply, every player, including that player, takes one last turn. Then the game ends and you add up final scores.',
     '["QUICK","STANDARD","DETAILED"]', '[]', '[]', '["Game End"]', FALSE),
    ('FINAL_SCORING', 10, 'Final scoring',
     'Recount route points to catch mistakes. Everyone reveals their tickets: add the value of each completed ticket and subtract the value of each unfinished one. The longest continuous path of a single color earns a 10-point bonus, and tied players each score it. The highest total wins.',
     '["QUICK","STANDARD","DETAILED"]', '[]',
     '["A path can loop or pass through a city more than once, but each train counts only once."]', '["Game End"]', FALSE),
    ('TIES', 11, 'Breaking a tie',
     'If players tie for the most points, the one who completed more tickets wins. If they are still tied, the tied player holding the longest path bonus wins. Otherwise they share the victory.',
     '["DETAILED"]', '[]', '[]', '["Game End"]', FALSE)
) AS lessons(section_key, section_order, title, explanation, depths, examples, tips, rule_references, setup_trigger);

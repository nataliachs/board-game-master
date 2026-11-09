package com.boardgamemaster.session.infrastructure;

import com.boardgamemaster.rag.application.RuleAnswer;
import com.boardgamemaster.session.domain.*;
import com.boardgamemaster.setup.domain.SetupProgress;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.boardgamemaster.session.domain.SessionException.Code.*;

/** Parameterized persistence; application services own transaction boundaries. */
@Repository
public class SessionRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SessionRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public TeachingFlow latestFlow(UUID version) {
        return findLatestFlow(version).orElseThrow(() -> new SessionException(TEACHING_FLOW_NOT_FOUND, "No teaching flow is configured for this version"));
    }
    public Optional<TeachingFlow> findLatestFlow(UUID version) {
        var ids = jdbc.queryForList("SELECT id FROM teaching_flows WHERE game_version_id=? ORDER BY created_at DESC, id DESC LIMIT 1", UUID.class, version);
        return ids.isEmpty() ? Optional.empty() : Optional.of(flow(ids.getFirst()));
    }
    public TeachingFlow flow(UUID id) {
        var rows = jdbc.query("SELECT * FROM teaching_flows WHERE id=?", (r, n) -> new TeachingFlow(r.getObject("id", UUID.class),
                r.getObject("game_version_id", UUID.class), r.getString("name"), r.getTimestamp("created_at").toInstant(), sections(id)), id);
        if (rows.isEmpty()) { throw new SessionException(TEACHING_FLOW_NOT_FOUND, "Teaching flow was not found"); }
        return rows.getFirst();
    }
    private List<TeachingSection> sections(UUID id) {
        return jdbc.query("SELECT * FROM teaching_sections WHERE teaching_flow_id=? ORDER BY section_order", (r, n) -> new TeachingSection(
                r.getString("section_key"), r.getInt("section_order"), r.getString("title"), r.getString("explanation"),
                read(r.getString("depths"), new TypeReference<Set<TeachingDepth>>() {}),
                read(r.getString("examples"), new TypeReference<List<String>>() {}),
                read(r.getString("tips"), new TypeReference<List<String>>() {}),
                read(r.getString("rule_references"), new TypeReference<List<String>>() {}), r.getBoolean("setup_trigger")), id);
    }
    public void insertFlow(TeachingFlow flow) {
        jdbc.update("INSERT INTO teaching_flows(id,game_version_id,name,created_at) VALUES (?,?,?,?)",
                flow.id(), flow.gameVersionId(), flow.name(), java.sql.Timestamp.from(flow.createdAt()));
        for (var section : flow.sections()) {
            jdbc.update("""
                    INSERT INTO teaching_sections(id,teaching_flow_id,section_key,section_order,title,explanation,depths,examples,tips,rule_references,setup_trigger)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)
                    """, UUID.randomUUID(), flow.id(), section.key(), section.order(), section.title(), section.explanation(),
                    write(section.depths()), write(section.examples()), write(section.tips()), write(section.references()), section.setupTrigger());
        }
    }
    public GameSession session(UUID id, boolean lock) {
        var rows = jdbc.query("""
                SELECT s.*,v.game_id FROM game_sessions s JOIN game_versions v ON v.id=s.game_version_id WHERE s.id=?
                """ + (lock ? " FOR UPDATE OF s" : ""), (r, n) -> mapSession(r), id);
        if (rows.isEmpty()) { throw new SessionException(SESSION_NOT_FOUND, "Session was not found"); }
        return rows.getFirst();
    }
    private GameSession mapSession(ResultSet r) throws SQLException {
        return new GameSession(r.getObject("id", UUID.class), r.getObject("game_id", UUID.class), r.getObject("game_version_id", UUID.class),
                r.getObject("teaching_flow_id", UUID.class), r.getInt("player_count"), TeachingDepth.valueOf(r.getString("teaching_depth")),
                SessionStage.valueOf(r.getString("stage")), GameSession.Status.valueOf(r.getString("status")), r.getString("current_section_key"),
                read(r.getString("completed_sections"), new TypeReference<Set<String>>() {}), r.getLong("revision"),
                r.getTimestamp("created_at").toInstant(), r.getTimestamp("ended_at") == null ? null : r.getTimestamp("ended_at").toInstant(),
                r.getObject("setup_flow_id") == null ? null : new SetupProgress(r.getObject("setup_flow_id", UUID.class),
                        r.getString("current_setup_step_key"), read(r.getString("completed_setup_steps"), new TypeReference<Set<String>>() {}),
                        r.getString("setup_return_section_key"), r.getBoolean("setup_completed")));
    }
    public void insertSession(GameSession s) {
        jdbc.update("""
                INSERT INTO game_sessions(id,game_version_id,teaching_flow_id,player_count,teaching_depth,stage,status,
                    current_section_key,completed_sections,revision,created_at,setup_flow_id)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, s.id(), s.gameVersionId(), s.teachingFlowId(), s.playerCount(), s.teachingDepth().name(), s.stage().name(),
                s.status().name(), s.currentSectionKey(), write(s.completedSections()), s.revision(), java.sql.Timestamp.from(s.createdAt()), s.setup() == null ? null : s.setup().flowId());
    }
    public void updateSession(GameSession s, long expectedRevision) {
        int updated = jdbc.update("""
                UPDATE game_sessions SET stage=?, status=?, current_section_key=?, completed_sections=?::jsonb, revision=?, ended_at=?,
                    setup_flow_id=?,current_setup_step_key=?,completed_setup_steps=?::jsonb,setup_return_section_key=?,setup_completed=?
                WHERE id=? AND revision=?
                """, s.stage().name(), s.status().name(), s.currentSectionKey(), write(s.completedSections()), s.revision(),
                s.endedAt() == null ? null : java.sql.Timestamp.from(s.endedAt()),
                s.setup() == null ? null : s.setup().flowId(), s.setup() == null ? null : s.setup().currentStepKey(),
                write(s.setup() == null ? Set.of() : s.setup().completedStepKeys()), s.setup() == null ? null : s.setup().returnSectionKey(),
                s.setup() != null && s.setup().completed(), s.id(), expectedRevision);
        if (updated != 1) { throw conflict(); }
    }
    public void claimMessageRevision(UUID id, long revision) {
        if (jdbc.update("UPDATE game_sessions SET revision=revision+1 WHERE id=? AND revision=? AND status='ACTIVE'", id, revision) != 1) {
            throw conflict();
        }
    }
    public List<ConversationMessage> messages(UUID session, long before, int limit) {
        var messages = jdbc.query("""
                SELECT * FROM conversation_messages WHERE session_id=? AND sequence_number<? ORDER BY sequence_number DESC LIMIT ?
                """, (r, n) -> new ConversationMessage(r.getObject("id", UUID.class), session, r.getLong("sequence_number"),
                ConversationMessage.Role.valueOf(r.getString("role")), r.getString("content"),
                r.getString("answer") == null ? null : read(r.getString("answer"), new TypeReference<RuleAnswer>() {}),
                r.getTimestamp("created_at").toInstant()), session, before, limit);
        return messages.reversed().stream().toList();
    }
    public void appendExchange(UUID session, String question, RuleAnswer answer) {
        if (answer.answer().length() > 16000) { throw new SessionException(INVALID_SESSION, "Answer exceeds session storage limit"); }
        long sequence = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_number),0) FROM conversation_messages WHERE session_id=?", Long.class, session);
        append(session, sequence + 1, "USER", question, null);
        append(session, sequence + 2, "ASSISTANT", answer.answer(), answer);
    }
    private void append(UUID session, long sequence, String role, String content, RuleAnswer answer) {
        jdbc.update("INSERT INTO conversation_messages(id,session_id,sequence_number,role,content,answer,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
                UUID.randomUUID(), session, sequence, role, content, answer == null ? null : write(answer), java.sql.Timestamp.from(Instant.now()));
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode session data"); }
    }
    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalStateException("Could not decode session data"); }
    }
    private SessionException conflict() { return new SessionException(SESSION_CONFLICT, "Session changed during this request; reload and retry"); }
}

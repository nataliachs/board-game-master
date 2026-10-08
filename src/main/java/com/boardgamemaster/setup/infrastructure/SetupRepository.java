package com.boardgamemaster.setup.infrastructure;

import com.boardgamemaster.setup.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.boardgamemaster.setup.domain.SetupException.Code.SETUP_FLOW_NOT_FOUND;

@Repository
public class SetupRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SetupRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Optional<SetupFlow> latest(UUID version) {
        var ids = jdbc.queryForList("SELECT id FROM setup_flows WHERE game_version_id=? ORDER BY created_at DESC,id DESC LIMIT 1", UUID.class, version);
        return ids.isEmpty() ? Optional.empty() : Optional.of(flow(ids.getFirst()));
    }
    public SetupFlow requireLatest(UUID version) {
        return latest(version).orElseThrow(() -> new SetupException(SETUP_FLOW_NOT_FOUND, "No setup flow is configured for this version"));
    }
    public SetupFlow flow(UUID id) {
        var rows = jdbc.query("SELECT * FROM setup_flows WHERE id=?", (r, n) -> new SetupFlow(id,
                r.getObject("game_version_id", UUID.class), r.getString("name"), r.getTimestamp("created_at").toInstant(), steps(id)), id);
        if (rows.isEmpty()) { throw new SetupException(SETUP_FLOW_NOT_FOUND, "Setup flow was not found"); }
        return rows.getFirst();
    }
    private List<SetupStep> steps(UUID id) {
        return jdbc.query("SELECT * FROM setup_steps WHERE setup_flow_id=? ORDER BY step_order", (r, n) -> new SetupStep(
                r.getString("step_key"), r.getInt("step_order"), r.getString("title"), r.getString("instruction"),
                read(r.getString("notes")), read(r.getString("warnings"))), id);
    }
    public void insert(SetupFlow flow) {
        jdbc.update("INSERT INTO setup_flows(id,game_version_id,name,created_at) VALUES (?,?,?,?)", flow.id(), flow.gameVersionId(), flow.name(), java.sql.Timestamp.from(flow.createdAt()));
        for (var step : flow.steps()) {
            jdbc.update("""
                    INSERT INTO setup_steps(id,setup_flow_id,step_key,step_order,title,instruction,notes,warnings)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """, UUID.randomUUID(), flow.id(), step.key(), step.order(), step.title(), step.instruction(), write(step.notes()), write(step.warnings()));
        }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode setup data"); }
    }
    private List<String> read(String value) {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); } catch (JsonProcessingException e) { throw new IllegalStateException("Could not decode setup data"); }
    }
}

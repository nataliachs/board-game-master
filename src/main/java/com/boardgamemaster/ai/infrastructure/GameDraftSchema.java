package com.boardgamemaster.ai.infrastructure;

import static com.boardgamemaster.ai.LlmException.Code.LLM_INVALID_RESPONSE;

import com.boardgamemaster.ai.GameDraft;
import com.boardgamemaster.ai.LlmException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict structured-output contract for a game draft, and a parser that rejects any other shape. */
final class GameDraftSchema {
    static final int MAX_STEPS = 30;
    static final int MAX_LESSONS = 40;
    private static final int MAX_LINES = 10;

    private GameDraftSchema() { }

    static Map<String, Object> schema() {
        var lines = Map.<String, Object>of("type", "array", "items", Map.of("type", "string"));
        var stepProps = new LinkedHashMap<String, Object>();
        stepProps.put("title", string());
        stepProps.put("instruction", string());
        stepProps.put("notes", lines);
        stepProps.put("warnings", lines);
        var lessonProps = new LinkedHashMap<String, Object>();
        lessonProps.put("title", string());
        lessonProps.put("explanation", string());
        lessonProps.put("quick", bool());
        lessonProps.put("standard", bool());
        lessonProps.put("detailed", bool());
        lessonProps.put("startsSetup", bool());
        lessonProps.put("examples", lines);
        lessonProps.put("tips", lines);
        lessonProps.put("references", lines);
        var props = new LinkedHashMap<String, Object>();
        props.put("name", string());
        props.put("publisher", string());
        props.put("category", string());
        props.put("minPlayers", integer());
        props.put("maxPlayers", integer());
        props.put("playTimeMinutes", integer());
        props.put("edition", string());
        props.put("language", string());
        props.put("description", string());
        props.put("setupSteps", Map.of("type", "array", "items", object(stepProps)));
        props.put("lessons", Map.of("type", "array", "items", object(lessonProps)));
        return object(props);
    }

    static GameDraft parse(JsonNode node) {
        require(node != null && node.isObject() && node.size() == 11);
        var steps = new ArrayList<GameDraft.Step>();
        var stepNodes = array(node, "setupSteps", MAX_STEPS);
        for (var step : stepNodes) {
            require(step.isObject() && step.size() == 4);
            steps.add(new GameDraft.Step(text(step, "title"), text(step, "instruction"), lines(step, "notes"), lines(step, "warnings")));
        }
        var lessons = new ArrayList<GameDraft.Lesson>();
        for (var lesson : array(node, "lessons", MAX_LESSONS)) {
            require(lesson.isObject() && lesson.size() == 9);
            lessons.add(new GameDraft.Lesson(text(lesson, "title"), text(lesson, "explanation"), flag(lesson, "quick"),
                    flag(lesson, "standard"), flag(lesson, "detailed"), flag(lesson, "startsSetup"),
                    lines(lesson, "examples"), lines(lesson, "tips"), lines(lesson, "references")));
        }
        return new GameDraft(text(node, "name"), text(node, "publisher"), text(node, "category"), number(node, "minPlayers"),
                number(node, "maxPlayers"), number(node, "playTimeMinutes"), text(node, "edition"), text(node, "language"), text(node, "description"),
                steps, lessons);
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "additionalProperties", false, "properties", properties,
                "required", List.copyOf(properties.keySet()));
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer"); }

    private static JsonNode array(JsonNode node, String field, int max) {
        var value = node.path(field);
        require(value.isArray() && value.size() <= max);
        return value;
    }
    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isTextual() && value.textValue().length() <= 8000);
        return value.textValue();
    }
    private static boolean flag(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isBoolean());
        return value.booleanValue();
    }
    private static int number(JsonNode node, String field) {
        var value = node.path(field);
        require(value.isIntegralNumber() && value.canConvertToInt());
        return value.intValue();
    }
    private static List<String> lines(JsonNode node, String field) {
        var value = array(node, field, MAX_LINES);
        var result = new ArrayList<String>();
        for (var line : value) {
            require(line.isTextual() && line.textValue().length() <= 2000);
            result.add(line.textValue());
        }
        return result;
    }
    private static void require(boolean valid) {
        if (!valid) { throw new LlmException(LLM_INVALID_RESPONSE, "The AI returned a draft in an unexpected shape; try the import again"); }
    }
}

package com.demo.courserag.repository;

import com.demo.courserag.model.UserProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 会话状态存取（SQLite session_state 表） */
@Repository
public class SessionRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserProfile loadProfile(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT profile_json FROM session_state WHERE id = ?", sessionId);
        if (rows.isEmpty()) return UserProfile.empty();
        String json = (String) rows.get(0).get("profile_json");
        if (json == null || json.isBlank()) return UserProfile.empty();
        try {
            return mapper.readValue(json, UserProfile.class);
        } catch (Exception e) {
            return UserProfile.empty();
        }
    }

    public void saveProfile(String sessionId, UserProfile profile) {
        try {
            String json = mapper.writeValueAsString(profile);
            jdbc.update("""
                INSERT INTO session_state (id, profile_json, status, updated_at) VALUES (?, ?, 'collecting', datetime('now'))
                ON CONFLICT(id) DO UPDATE SET profile_json=excluded.profile_json, updated_at=datetime('now')
                """, sessionId, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** 消息历史（最近 N 条） */
    public List<Map<String, String>> loadHistory(String sessionId, int maxTurns) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT history_json FROM session_state WHERE id = ?", sessionId);
        if (rows.isEmpty()) return new ArrayList<>();
        String json = (String) rows.get(0).get("history_json");
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    public void appendHistory(String sessionId, String role, String content) {
        List<Map<String, String>> history = loadHistory(sessionId, 100);
        history.add(Map.of("role", role, "content", content));
        if (history.size() > 40) {
            history = history.subList(history.size() - 40, history.size());
        }
        try {
            String json = mapper.writeValueAsString(history);
            jdbc.update("""
                INSERT INTO session_state (id, history_json, updated_at) VALUES (?, ?, datetime('now'))
                ON CONFLICT(id) DO UPDATE SET history_json=excluded.history_json, updated_at=datetime('now')
                """, sessionId, json);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}

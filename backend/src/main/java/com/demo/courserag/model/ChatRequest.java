package com.demo.courserag.model;

import java.util.List;
import java.util.Map;

/** 前端 /api/chat 请求体 */
public record ChatRequest(
        String sessionId,
        String userMsg,
        List<Map<String, Object>> selectedData  // 页面选中的课程数据
) {}

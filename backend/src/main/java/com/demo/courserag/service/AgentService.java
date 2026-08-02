package com.demo.courserag.service;

import com.demo.courserag.model.ChatRequest;
import com.demo.courserag.model.UserProfile;
import com.demo.courserag.repository.SessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 核心：五步管线（改写 → 意图 → 状态 → 工具循环 → 生成）
 * 通过 EventSink 把推理过程（thinking / tool_start / tool_end / answer）推给前端（SSE）。
 */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    public interface EventSink {
        void emit(String event, String data);
    }

    private final OpenAiChatModel chatModel;
    private final ObjectMapper mapper;
    private final ToolExecutor tools;
    private final SessionRepository sessions;
    private final int maxToolRounds;

    private static final String SYSTEM_PROMPT = """
        你是"语培优选"课程顾问，帮助用户选择英语课程并报价。
        你必须遵守：
        1. 只依据工具返回的结果回答；价格、时长、套餐信息一律来自工具输出，严禁自己编造或猜测数字。
        2. 多轮对话中，用户会用"它/那个/这个"指代上文提到的课程，回答前先结合历史理解上下文。
        3. 工具使用策略：
           - 用户问某课程价格 → 调 query_price
           - 用户想了解课程内容 → 调 search_content
           - 用户问时长/能否学完 → 调 check_duration
           - 用户问套餐/优惠/包月包年 → 调 match_package
           - 用户给需求（科目/水平/时间/预算）要推荐 → 调 search_courses；当需求基本明确（目标+水平+时间）时 → 调 finalize_recommend 出最终方案
           - 用户确认购买 → 调 create_order
        4. 检索不到的信息明确说"暂无此信息"，不要编造。
        5. 回答用中文，简洁，推荐时给出理由，价格用 ¥ 显示。
        6. 若本轮信息不足（如缺水平、缺时间），可用一句话追问用户，一次只问一个关键问题。
        """;

    public AgentService(OpenAiChatModel chatModel, ToolExecutor tools, SessionRepository sessions,
                        ObjectMapper mapper,
                        @Value("${app.agent-max-tool-rounds:6}") int maxToolRounds) {
        this.chatModel = chatModel;
        this.tools = tools;
        this.sessions = sessions;
        this.mapper = mapper;
        this.maxToolRounds = maxToolRounds;
    }

    /** 处理一轮对话，事件经 sink 推送 */
    public void handleTurn(ChatRequest req, EventSink sink) {
        String sessionId = req.sessionId();
        UserProfile profile = sessions.loadProfile(sessionId);

        // 页面选数据 → 预填画像
        if (req.selectedData() != null && !req.selectedData().isEmpty()) {
            applySelectedData(profile, req.selectedData());
            sessions.saveProfile(sessionId, profile);
            emit(sink, "thinking", "已接收所选课程数据，结合它为您解答");
        }

        String userMsg = req.userMsg();
        sessions.appendHistory(sessionId, "user", userMsg);
        List<Map<String, String>> history = sessions.loadHistory(sessionId, 20);

        // ① 查询改写（指代消解）
        String rewritten = rewrite(userMsg, history, profile);
        emit(sink, "thinking", "查询改写：" + rewritten);

        // ② 意图分类
        String intent = classify(rewritten);
        emit(sink, "thinking", "意图识别：" + intent);

        // 闲聊 → 不检索直接聊
        if ("chitchat".equals(intent)) {
            String answer = chat(sessionId, "你是友善的课程顾问，简短回应并顺势询问是否需要课程推荐。", userMsg);
            emit(sink, "answer", answer);
            sessions.appendHistory(sessionId, "assistant", answer);
            return;
        }

        // ③ 状态更新（约束累积）
        profile = updateProfile(profile, userMsg);
        sessions.saveProfile(sessionId, profile);
        emit(sink, "thinking", "需求画像：" + compactProfile(profile));

        // ④ 工具循环（内层多轮）
        List<Message> messages = buildMessages(history, userMsg, profile);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(0.3)
                .toolCallbacks(tools.toolCallbacks())
                .internalToolExecutionEnabled(false)
                .build();

        String finalAnswer = null;
        for (int round = 0; round < maxToolRounds; round++) {
            ChatResponse resp = chatModel.call(new Prompt(messages, options));
            Generation gen = resp.getResult();
            AssistantMessage out = (AssistantMessage) gen.getOutput();
            List<AssistantMessage.ToolCall> calls = out.getToolCalls();

            if (calls == null || calls.isEmpty()) {
                finalAnswer = out.getText();
                break;
            }
            // 先把含 tool_calls 的 assistant 消息加入对话流（DeepSeek 要求 tool 消息必须紧跟 tool_calls）
            messages.add(out);
            for (AssistantMessage.ToolCall call : calls) {
                Map<String, Object> args = parseArgs(call.arguments());
                emit(sink, "tool_start", "{\"tool\":\"" + call.name() + "\",\"args\":" + call.arguments() + "}");
                String result = tools.execute(call.name(), args);
                emit(sink, "tool_end", "{\"tool\":\"" + call.name() + "\",\"result\":" + jsonSafe(result) + "}");
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result)))
                        .build());
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "抱歉，我没有能得出答案，请您换个说法再试一次。";
        }

        // ⑤ 生成/输出
        emit(sink, "answer", finalAnswer);
        sessions.appendHistory(sessionId, "assistant", finalAnswer);
        emit(sink, "done", "{}");
    }

    // ------------------------------------------------------------------
    // 第一步：查询改写
    // ------------------------------------------------------------------
    private String rewrite(String userMsg, List<Map<String, String>> history, UserProfile profile) {
        StringBuilder hist = new StringBuilder();
        for (Map<String, String> h : history) {
            hist.append(h.get("role")).append(": ").append(h.get("content")).append("\n");
        }
        String prompt = """
            你负责把多轮对话中用户的最后一句话改写成一条"自包含、可直接检索"的完整问题。
            规则：补充指代（它/那个/这个）、补全省略的上下文；保持原意；只输出改写后的一句话，不要任何解释。
            对话历史：
            %s
            当前用户消息：%s
            改写结果：
            """.formatted(hist, userMsg);
        return callText(prompt, 0.0).trim();
    }

    // ------------------------------------------------------------------
    // 第二步：意图分类
    // ------------------------------------------------------------------
    private String classify(String rewritten) {
        String prompt = """
            判断用户这句话的意图，只输出以下之一：
            course_recommend（要推荐课程/学什么）
            price_query（问价格/多少钱）
            content_query（问课程内容/学什么知识）
            package_query（问套餐/包月/包年/优惠）
            plan_query（问时长/多久学完/能否完成）
            chitchat（问候/闲聊/感谢/无关话题）
            用户消息：%s
            意图：
            """.formatted(rewritten);
        String out = callText(prompt, 0.0).trim().toLowerCase();
        if (out.contains("course_recommend")) return "course_recommend";
        if (out.contains("price_query")) return "price_query";
        if (out.contains("content_query")) return "content_query";
        if (out.contains("package_query")) return "package_query";
        if (out.contains("plan_query")) return "plan_query";
        return "chitchat";
    }

    // ------------------------------------------------------------------
    // 第三步：状态更新（约束累积）
    // ------------------------------------------------------------------
    private UserProfile updateProfile(UserProfile profile, String userMsg) {
        String prompt = """
            从用户消息中提取课程需求字段，输出 JSON（不要输出其他内容）：
            {"targetSubject":"科目","targetCourse":"目标课程(不知道给空串)","level":"水平(零基础/入门/基础/中级/冲刺/强化，未提给空串)","grade":"年级(小学/初中/高中/成人，未提给空串)","timeLimitDays":数字或null,"budget":数字或null,"learningGoal":"学习目标(未提给空串)"}
            用户消息：%s
            """.formatted(userMsg);
        String out = callText(prompt, 0.0);
        try {
            JsonNode node = mapper.readTree(extractJson(out));
            if (node.has("targetSubject") && !node.get("targetSubject").asText().isBlank())
                profile.targetSubject = node.get("targetSubject").asText();
            if (node.has("targetCourse") && !node.get("targetCourse").asText().isBlank())
                profile.targetCourse = node.get("targetCourse").asText();
            if (node.has("level") && !node.get("level").asText().isBlank()) {
                profile.level = node.get("level").asText();
                profile.hasLevel = true;
            }
            if (node.has("grade") && !node.get("grade").asText().isBlank())
                profile.grade = node.get("grade").asText();
            if (node.has("timeLimitDays") && !node.get("timeLimitDays").isNull()) {
                profile.timeLimitDays = node.get("timeLimitDays").asInt();
                profile.hasTimeLimit = true;
            }
            if (node.has("budget") && !node.get("budget").isNull()) {
                profile.budget = node.get("budget").asDouble();
                profile.hasBudget = true;
            }
            if (node.has("learningGoal") && !node.get("learningGoal").asText().isBlank())
                profile.learningGoal = node.get("learningGoal").asText();
        } catch (Exception e) {
            log.warn("画像解析失败: {}", out);
        }
        return profile;
    }

    private void applySelectedData(UserProfile profile, List<Map<String, Object>> selected) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> item : selected) {
            Object n = item.get("name");
            if (n != null) names.add(n.toString());
        }
        if (!names.isEmpty()) {
            profile.targetCourse = names.get(0);
            if (profile.targetSubject.isBlank()) profile.targetSubject = "英语";
        }
        profile.recommended = names;
    }

    // ------------------------------------------------------------------
    // 第四步：消息组装（system + history + 当前轮）
    // ------------------------------------------------------------------
    private List<Message> buildMessages(List<Map<String, String>> history, String userMsg, UserProfile profile) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM_PROMPT));
        // 追加画像信息
        messages.add(new SystemMessage("当前已知用户需求画像：" + mapperValue(compactProfile(profile)) + "。若画像已有目标课程，用户后续提问默认指该课程。"));
        int start = Math.max(0, history.size() - 16);
        for (int i = start; i < history.size(); i++) {
            Map<String, String> h = history.get(i);
            if ("user".equals(h.get("role"))) messages.add(new UserMessage(h.get("content")));
            else messages.add(new AssistantMessage(h.get("content")));
        }
        messages.add(new UserMessage(userMsg));
        return messages;
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------
    private String callText(String prompt, double temperature) {
        Prompt p = new Prompt(prompt, OpenAiChatOptions.builder().temperature(temperature).build());
        try {
            ChatResponse resp = chatModel.call(p);
            return resp.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage());
            return "";
        }
    }

    private String chat(String sessionId, String system, String user) {
        Prompt p = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
        try {
            return chatModel.call(p).getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("闲聊调用失败", e);
            return "抱歉，我暂时无法回应。";
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String extractJson(String s) {
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return s;
    }

    private String compactProfile(UserProfile p) {
        Map<String, Object> m = new HashMap<>();
        m.put("目标科目", p.targetSubject);
        m.put("目标课程", p.targetCourse);
        m.put("水平", p.level);
        m.put("年级", p.grade);
        m.put("时间限制(天)", p.timeLimitDays);
        m.put("预算", p.budget);
        m.put("学习目标", p.learningGoal);
        m.put("已推荐", p.recommended);
        return mapperValue(m);
    }

    private String mapperValue(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private String jsonSafe(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
    }

    private void emit(EventSink sink, String event, String data) {
        if (sink != null) sink.emit(event, data);
    }
}

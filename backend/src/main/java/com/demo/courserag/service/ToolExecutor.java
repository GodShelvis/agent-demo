package com.demo.courserag.service;

import com.demo.courserag.model.CourseBrief;
import com.demo.courserag.model.PackageInfo;
import com.demo.courserag.model.ParagraphDoc;
import com.demo.courserag.model.PricingInfo;
import com.demo.courserag.repository.CourseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 工具执行层：AI 只填参数（Map），这里执行预写好的业务查询。
 * 返回字符串结果供 LLM 阅读。
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);
    private final CourseRepository repo;
    private final RetrievalService retrieval;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger orderSeq = new AtomicInteger(1000);

    private final Map<String, Function<Map<String, Object>, String>> tools = new LinkedHashMap<>();

    public ToolExecutor(CourseRepository repo, RetrievalService retrieval) {
        this.repo = repo;
        this.retrieval = retrieval;
        registerTools();
    }

    private void registerTools() {
        tools.put("search_courses", this::searchCourses);
        tools.put("query_price", this::queryPrice);
        tools.put("match_package", this::matchPackage);
        tools.put("search_content", this::searchContent);
        tools.put("check_duration", this::checkDuration);
        tools.put("finalize_recommend", this::finalizeRecommend);
        tools.put("create_order", this::createOrder);
    }

    /** 执行工具，返回结果文本 */
    public String execute(String name, Map<String, Object> args) {
        Function<Map<String, Object>, String> fn = tools.get(name);
        if (fn == null) return "未知工具：" + name;
        try {
            return fn.apply(args == null ? Map.of() : args);
        } catch (Exception e) {
            log.error("工具 {} 执行失败", name, e);
            return "工具执行失败：" + e.getMessage();
        }
    }

    public Set<String> toolNames() {
        return tools.keySet();
    }

    // ------------------------------------------------------------------
    // 工具实现
    // ------------------------------------------------------------------

    /** ① 课程检索：按约束过滤候选池 */
    private String searchCourses(Map<String, Object> a) {
        String subject = str(a.get("subject"));
        String level = str(a.get("level"));
        String grade = str(a.get("grade"));
        Boolean afterSchool = a.get("is_after_school") == null ? null
                : Boolean.valueOf(a.get("is_after_school").toString());
        Integer durationMax = a.get("duration_max_days") == null ? null
                : ((Number) a.get("duration_max_days")).intValue();
        Double budget = a.get("budget_max") == null ? null
                : ((Number) a.get("budget_max")).doubleValue();

        final String key;
        if (level != null && !level.isBlank()) {
            key = level;
        } else if (subject != null && !subject.isBlank()) {
            key = subject;
        } else {
            key = "";
        }

        List<CourseBrief> all = repo.findAllCourses();
        List<CourseBrief> candidates = all.stream()
                .filter(c -> grade == null || grade.isBlank() || c.grade().contains(grade))
                .filter(c -> afterSchool == null || c.isAfterSchool() == afterSchool)
                .filter(c -> durationMax == null || c.durationDays() <= durationMax)
                .filter(c -> budget == null || c.price() <= budget)
                // 水平/科目关键词匹配
                .filter(c -> key.isBlank() || c.name().contains(key) || c.description().contains(key)
                        || c.grade().contains(key))
                .toList();

        if (candidates.isEmpty()) {
            return "没有找到符合条件的课程。";
        }
        StringBuilder sb = new StringBuilder("符合条件课程（" + candidates.size() + " 门）：\n");
        for (CourseBrief c : candidates) {
            sb.append("- ").append(c.name()).append("｜教材：").append(c.textbook())
              .append("｜年级：").append(c.grade())
              .append("｜时长：").append(c.durationDays()).append("天")
              .append("｜价格：¥").append((int) c.price()).append("\n");
        }
        return sb.toString();
    }

    /** ② 价格查询 */
    private String queryPrice(Map<String, Object> a) {
        String courseName = str(a.get("course_name"));
        CourseBrief c = repo.findCourseByName(courseName);
        if (c == null) return "未找到课程「" + courseName + "」，请确认课程名称。";
        List<PricingInfo> pricing = repo.findPricingByCourse(c.id());
        StringBuilder sb = new StringBuilder();
        sb.append("课程「").append(c.name()).append("」价格：\n");
        for (PricingInfo p : pricing) {
            sb.append("- ").append(typeName(p.type())).append("：¥").append((int) p.price())
              .append("（").append(p.durationDays()).append("天）\n");
        }
        // 关联套餐
        List<PackageInfo> pkgs = repo.findAllPackages().stream()
                .filter(pk -> pk.courseNames().contains(c.name()))
                .toList();
        if (!pkgs.isEmpty()) {
            sb.append("可搭配套餐：\n");
            for (PackageInfo pk : pkgs) {
                sb.append("- ").append(pk.name()).append("：¥").append((int) pk.price())
                  .append("（").append(pk.durationDays()).append("天，含：")
                  .append(String.join("、", pk.courseNames())).append("）\n");
            }
        }
        return sb.toString();
    }

    /** ③ 套餐匹配 + 性价比（省钱计算由业务层完成） */
    private String matchPackage(Map<String, Object> a) {
        String target = str(a.get("target"));
        Integer timeLimit = a.get("time_limit_days") == null ? null : ((Number) a.get("time_limit_days")).intValue();
        List<PackageInfo> pkgs = repo.findAllPackages();
        StringBuilder sb = new StringBuilder();
        for (PackageInfo pk : pkgs) {
            if (timeLimit != null && pk.durationDays() > timeLimit) continue;
            // 计算单买累加
            double singleSum = 0;
            for (String cn : pk.courseNames()) {
                CourseBrief c = repo.findCourseByName(cn);
                if (c != null) singleSum += c.price();
            }
            sb.append("- ").append(pk.name()).append("：¥").append((int) pk.price())
              .append("（").append(pk.durationDays()).append("天）");
            if (!pk.courseNames().isEmpty()) {
                sb.append("，含：").append(String.join("、", pk.courseNames()));
                double save = singleSum - pk.price();
                if (save > 0) sb.append("，单买合计 ¥").append((int) singleSum)
                        .append("，套餐省 ¥").append((int) save);
            }
            sb.append("\n");
        }
        if (sb.length() == 0) {
            sb.append("当前约束下没有匹配的套餐。");
            return sb.toString();
        }
        return "匹配套餐：\n" + sb;
    }

    /** ④ 内容检索（向量 + 关键词兜底） */
    private String searchContent(Map<String, Object> a) {
        String course = str(a.get("course"));
        String topic = str(a.get("topic"));
        String query = (topic == null || topic.isBlank()) ? course : course + " " + topic;
        List<ParagraphDoc> docs = retrieval.searchContent(query, course);
        if (docs.isEmpty()) return "知识库中没有检索到相关内容。";
        StringBuilder sb = new StringBuilder("检索到的课程内容：\n");
        for (ParagraphDoc d : docs) {
            sb.append("【").append(d.courseName()).append(" · ").append(d.chapterTitle())
              .append(" · ").append(d.unitTitle()).append("】\n")
              .append(d.content()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** ⑤ 时长匹配：可行性检查 + 冲突兜底替代方案 */
    private String checkDuration(Map<String, Object> a) {
        String courseName = str(a.get("course"));
        int target = a.get("target_days") == null ? 30 : ((Number) a.get("target_days")).intValue();
        CourseBrief c = repo.findCourseByName(courseName);
        if (c == null) {
            // 替代：找时长满足的课
            List<CourseBrief> fits = repo.findAllCourses().stream()
                    .filter(x -> x.durationDays() <= target)
                    .toList();
            if (fits.isEmpty()) return "未找到课程，也没有满足 " + target + " 天内的课程。";
            StringBuilder sb = new StringBuilder("未找到该课程。可满足 " + target + " 天内完成的课程：\n");
            for (CourseBrief f : fits) sb.append("- ").append(f.name()).append("（")
                    .append(f.durationDays()).append("天，¥").append((int) f.price()).append("）\n");
            return sb.toString();
        }
        if (c.durationDays() <= target) {
            return "课程「" + c.name() + "」标准时长 " + c.durationDays() + " 天，可在 " + target + " 天内完成，满足要求。";
        }
        // 冲突兜底：找替代
        StringBuilder sb = new StringBuilder("课程「" + c.name() + "」需要 " + c.durationDays()
                + " 天，无法在 " + target + " 天内完成。\n替代建议：\n");
        List<CourseBrief> fits = repo.findAllCourses().stream()
                .filter(x -> x.durationDays() <= target && !x.name().equals(c.name()))
                .toList();
        for (CourseBrief f : fits) {
            sb.append("- ").append(f.name()).append("（").append(f.durationDays())
              .append("天，¥").append((int) f.price()).append("）\n");
        }
        // 套餐替代
        List<PackageInfo> pkgs = repo.findAllPackages().stream()
                .filter(p -> p.durationDays() <= target)
                .toList();
        if (!pkgs.isEmpty()) {
            sb.append("套餐选项：\n");
            for (PackageInfo p : pkgs) {
                sb.append("- ").append(p.name()).append("（").append(p.durationDays())
                  .append("天，¥").append((int) p.price()).append("）\n");
            }
        }
        return sb.toString();
    }

    /** ⑥ 收口推荐：加权评分 Top-N */
    private String finalizeRecommend(Map<String, Object> a) {
        String subject = str(a.get("subject"));
        String level = str(a.get("level"));
        String grade = str(a.get("grade"));
        Integer timeLimit = a.get("time_limit_days") == null ? null : ((Number) a.get("time_limit_days")).intValue();
        Double budget = a.get("budget") == null ? null : ((Number) a.get("budget")).doubleValue();

        List<CourseBrief> all = repo.findAllCourses();
        List<Scored> scored = new ArrayList<>();
        for (CourseBrief c : all) {
            if (subject != null && !subject.isBlank() && !c.name().contains(subject)
                    && !c.description().contains(subject)) continue;
            int s = 0;
            // 时间匹配 +40
            if (timeLimit != null) s += (c.durationDays() <= timeLimit) ? 40 : 0;
            // 预算匹配 +20
            if (budget != null) s += (c.price() <= budget) ? 20 : 0;
            // 水平匹配 +30（关键词）
            if (level != null && !level.isBlank()) {
                boolean easy = level.contains("零基础") || level.contains("入门") || level.contains("基础");
                boolean hard = level.contains("冲刺") || level.contains("强化") || level.contains("提高");
                if (easy && (c.name().contains("基础") || c.name().contains("入门")
                        || c.name().contains("拼读") || c.name().contains("情景"))) s += 30;
                else if (hard && (c.name().contains("冲刺") || c.name().contains("强化"))) s += 30;
                else s += 15;
            }
            // 性价比 +10（单位时长价格越低越高）
            s += 10 - Math.min(10, (int) (c.price() / c.durationDays() / 30));
            scored.add(new Scored(c, s));
        }
        scored.sort((x, y) -> Integer.compare(y.score(), x.score()));
        StringBuilder sb = new StringBuilder("推荐方案（按匹配度排序）：\n");
        int n = Math.min(3, scored.size());
        for (int i = 0; i < n; i++) {
            Scored sc = scored.get(i);
            sb.append((i + 1) + ". ").append(sc.course().name())
              .append("｜匹配度 ").append(sc.score())
              .append("｜时长 ").append(sc.course().durationDays()).append("天")
              .append("｜¥").append((int) sc.course().price())
              .append("｜").append(sc.course().description()).append("\n");
        }
        // 附套餐
        List<PackageInfo> pkgs = repo.findAllPackages().stream()
                .filter(p -> timeLimit == null || p.durationDays() <= timeLimit)
                .toList();
        if (!pkgs.isEmpty()) {
            sb.append("\n可选套餐：\n");
            for (PackageInfo p : pkgs) {
                sb.append("- ").append(p.name()).append("：¥").append((int) p.price())
                  .append("（").append(p.durationDays()).append("天）\n");
            }
        }
        return sb.toString();
    }

    /** ⑦ 下单（demo 模拟） */
    private String createOrder(Map<String, Object> a) {
        String courseName = str(a.get("course_name"));
        String pkgName = str(a.get("package_name"));
        Object price = a.get("price");
        String orderNo = "CO-" + java.time.LocalDate.now().toString().replace("-", "")
                + "-" + orderSeq.incrementAndGet();
        String what = pkgName != null && !pkgName.isBlank() ? pkgName : courseName;
        return "订单已生成：订单号 " + orderNo + "，商品「" + what + "」"
                + (price != null ? "，金额 ¥" + price : "") + "。请在 15 分钟内完成支付。";
    }

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private String typeName(String t) {
        return switch (t) {
            case "single" -> "单课";
            case "package" -> "套餐";
            case "monthly" -> "包月";
            case "yearly" -> "包年";
            default -> t;
        };
    }

    private record Scored(CourseBrief course, int score) {}

    // ------------------------------------------------------------------
    // 工具 Schema（提供给 LLM 的 Function Calling 定义）
    // ------------------------------------------------------------------
    public List<ToolCallback> toolCallbacks() {
        return List.of(
            buildCallback("search_courses",
                "按约束条件检索课程候选。参数：subject(科目/主题,可选), level(水平:零基础/入门/基础/冲刺/强化,可选), grade(年级,可选), is_after_school(是否课外,可选), duration_max_days(最大时长天数,可选), budget_max(最高预算,可选)",
                "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\"},\"level\":{\"type\":\"string\"},\"grade\":{\"type\":\"string\"},\"is_after_school\":{\"type\":\"boolean\"},\"duration_max_days\":{\"type\":\"integer\"},\"budget_max\":{\"type\":\"number\"}},\"required\":[]}"),
            buildCallback("query_price",
                "查询某课程的价格与可搭配套餐。参数：course_name(课程名称,必填)",
                "{\"type\":\"object\",\"properties\":{\"course_name\":{\"type\":\"string\"}},\"required\":[\"course_name\"]}"),
            buildCallback("match_package",
                "按目标课程与时间约束匹配套餐，并计算与单买相比节省的金额。参数：target(目标课程/主题), time_limit_days(时间限制天数)",
                "{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"},\"time_limit_days\":{\"type\":\"integer\"}},\"required\":[]}"),
            buildCallback("search_content",
                "在课程知识库中检索章节/单元/段落内容。参数：course(课程名称,可选), topic(主题关键词)",
                "{\"type\":\"object\",\"properties\":{\"course\":{\"type\":\"string\"},\"topic\":{\"type\":\"string\"}},\"required\":[\"topic\"]}"),
            buildCallback("check_duration",
                "检查某课程能否在指定天数内完成，若不能则给出替代课程与套餐建议。参数：course(课程名称), target_days(目标天数)",
                "{\"type\":\"object\",\"properties\":{\"course\":{\"type\":\"string\"},\"target_days\":{\"type\":\"integer\"}},\"required\":[\"course\",\"target_days\"]}"),
            buildCallback("finalize_recommend",
                "需求已收集完整时调用：基于需求画像做加权评分，输出最终 Top-N 推荐方案与可选套餐。参数：subject(科目), level(水平), grade(年级), time_limit_days(时间限制天数), budget(预算)",
                "{\"type\":\"object\",\"properties\":{\"subject\":{\"type\":\"string\"},\"level\":{\"type\":\"string\"},\"grade\":{\"type\":\"string\"},\"time_limit_days\":{\"type\":\"integer\"},\"budget\":{\"type\":\"number\"}},\"required\":[]}"),
            buildCallback("create_order",
                "用户确认购买时生成订单。参数：course_name(课程名称) 或 package_name(套餐名称), price(金额)",
                "{\"type\":\"object\",\"properties\":{\"course_name\":{\"type\":\"string\"},\"package_name\":{\"type\":\"string\"},\"price\":{\"type\":\"number\"}},\"required\":[]}")
        );
    }

    /** 构造 ToolCallback（AI 填参数 → 业务层执行；参数直接由 JSON Schema 描述） */
    private ToolCallback buildCallback(String name, String description, String inputSchema) {
        return FunctionToolCallback.builder(name, (BiFunction<Map<String, Object>, ToolContext, String>)
                (args, ctx) -> execute(name, args))
                .description(description)
                .inputSchema(inputSchema)
                .inputType(new ParameterizedTypeReference<Map<String, Object>>() {})
                .build();
    }
}

# 实现说明文档：多轮智能问答 Demo（课程学习系统）

> 版本：v1.0（2026-08-02）
> 本文档记录**最终落地实现**：技术栈、架构、核心机制、与设计方案的差异、以及已实现/未实现清单。
> 配套文档：`方案设计_多轮智能问答课程推荐系统.md`（设计稿）、`验收文档_多轮智能问答Demo.md`（验收指引）、`调研报告_多轮智能问答开源项目.md`（调研）。

---

## 一、一句话总结

已交付一个可运行的 **课程售课咨询 AI 助手**：左侧课程货架（可拖拽/点选进入咨询）+ 中间多轮对话 + 右侧 AI 推理过程实时可视化（工具调用轨迹直播）。后端基于 Spring AI 实现五步管线（查询改写 → 意图识别 → 约束累积 → 工具循环 → 生成），使用 DeepSeek 在线大模型 + SiliconFlow 免费向量模型，本地 SQLite 存业务数据。

---

## 二、最终技术栈（落地清单）

| 环节 | 设计方案 | **最终实现** | 差异说明 |
|---|---|---|---|
| 后端框架 | Java 21 + Spring Boot | **Java 17 + Spring Boot 3.5.3** | 本机 JDK 17，Spring Boot 3.5 支持 17+ |
| AI 框架 | Spring AI | **Spring AI 1.1.6** | 与设计一致 |
| 生成模型 | DeepSeek-V4-Flash | **DeepSeek-V4-Flash（deepseek-v4-flash）** | 与设计一致，官方 API（OpenAI 兼容） |
| 理解模型 | 同模型非思考模式 | **同模型（prompt 约束）** | 与设计一致，未单独配思考模式 |
| Embedding | SiliconFlow bge-m3（免费） | **SiliconFlow BAAI/bge-m3（免费）** | 与设计一致，在线 API |
| 向量库 | ChromaDB（Docker） | **SimpleVectorStore（内存）** | ⚠️ 变更：ChromaDB 新版废弃 v1 API 且 Docker 拉镜像慢，改为内存向量库（demo 数据量小，重启自动重建索引） |
| 关系库 | SQLite | **SQLite（backend/data/course.db）** | 与设计一致，本地文件 |
| 会话存储 | 关系库 | **关系库（session_state 表）** | 与设计一致 |
| 前端 | Ant Design X | **Ant Design X 1.6.1（Bubble + Sender + ThoughtChain）** | ⚠️ 组件差异：1.6 无 Conversation 组件，改用 Bubble（消息气泡）+ Sender（输入框）+ ThoughtChain（推理链） |
| 端口 | 8080 | **8081** | 8080 被本机其他服务占用 |
| 部署 | Docker 编排 | **本地进程（./start.sh）** | ⚠️ 变更：不依赖 Docker，全在线 API，零本地服务 |

---

## 三、系统架构（最终实现）

```
┌──────────────────────────────────────────────────────────────┐
│ 前端：Vite + React + Ant Design X（http://localhost:5173）      │
│  ├─ 左侧：课程货架（10 门课卡片，可点 + 或拖拽进输入区）         │
│  ├─ 中间：Bubble 消息流（多轮对话）                             │
│  ├─ 输入：Sender（Enter 发送，可拖放课程）                     │
│  └─ 右侧：ThoughtChain 推理面板（实时工具调用轨迹）             │
└──────────────────────────────┬───────────────────────────────┘
                 /api/chat (SSE: thinking/tool_start/tool_end/answer/done)
┌──────────────────────────────▼───────────────────────────────┐
│ 后端：Spring Boot 3.5.3 + Spring AI 1.1.6（http://localhost:8081）│
│  ├─ AgentService：五步管线（改写→意图→画像→工具循环→生成）       │
│  ├─ ToolExecutor：7 个工具（AI 填参数，业务层执行）              │
│  ├─ CourseRepository：SQLite 业务数据（JdbcTemplate）           │
│  ├─ SessionRepository：会话画像 + 历史（SQLite）                │
│  ├─ RetrievalService：内容检索（向量 + 关键词兜底）              │
│  └─ EmbeddingIndexer：启动时向量化 18 个段落写入内存向量库       │
└───────────────┬───────────────────────────────┬───────────────┘
                │                               │
        DeepSeek API（对话/改写/意图）   SiliconFlow API（bge-m3 向量）
```

---

## 四、核心机制：五步管线（AgentService）

每轮用户消息进入后：

### ① 查询改写（指代消解）
把「历史对话 + 当前问题」交给 DeepSeek，重写成自包含的完整问题。
> 实测：用户第 3 轮说"那大概要多少钱？" → 改写为"推荐的这两门英语课程大概需要多少钱？" → 正确命中两门课价格。

### ② 意图分类（场景路由）
LLM 输出六类之一：`course_recommend / price_query / content_query / package_query / plan_query / chitchat`。
- chitchat → 不检索直接聊；其余走对应业务链。

### ③ 状态更新（约束累积）
LLM 从本轮话里抽取约束，合并进需求画像（UserProfile），持久化到 SQLite。
> 画像字段：目标科目/目标课程/水平/年级/时间限制(天)/预算/学习目标/已推荐。

### ④ 工具循环（内层多轮，全程事件推送）
- 用 `FunctionToolCallback` 暴露 7 个工具给模型（`internalToolExecutionEnabled=false`，**自己控制执行**以拿到中间事件）
- 模型返回 tool_calls → 先追加 assistant 消息（含 tool_calls，DeepSeek 要求 tool 消息紧跟 tool_calls）→ 逐个执行工具 → 追加 ToolResponseMessage → 继续循环，直到模型直接输出文本
- 每步通过 SSE 推送 `tool_start` / `tool_end`，前端实时渲染

### ⑤ 生成回答
工具循环产出的最终文本即回答，通过 `answer` 事件推送；回答基于工具返回的真实数据（价格/时长/套餐），防幻觉。

---

## 五、七个工具（ToolExecutor）

| 工具 | 作用 | 触发场景 |
|---|---|---|
| `search_courses` | 按约束过滤课程候选池 | 推荐 |
| `query_price` | 查单课价格 + 可搭配套餐 | 价格咨询 |
| `match_package` | 匹配套餐 + 计算省钱金额 | 套餐/预算 |
| `search_content` | 向量检索章节/单元/段落 | 内容咨询 |
| `check_duration` | 时长可行性检查 + 冲突兜底替代 | 学习规划 |
| `finalize_recommend` | 加权评分输出 Top-N 最终方案 | 需求集齐收口 |
| `create_order` | 生成订单号（demo 模拟） | 用户确认下单 |

**关键设计**：AI 只填结构化参数（JSON），SQL 由人工预写的参数化模板执行——AI 不直接碰数据库。

---

## 六、数据模型（SQLite）

| 表 | 内容 | 数据量 |
|---|---|---|
| subject | 科目 | 1（英语） |
| course | 课程（名称/教材/年级/是否课外/时长/简介） | 10 |
| chapter / unit / paragraph | 章节→单元→段落（知识点） | 4章+（18 段落） |
| pricing | 定价（单卖/套餐/包月/包年） | 10 单卖 |
| package / package_course | 套餐 + 套餐-课程映射 | 5 |
| session_state | 会话画像 JSON + 历史 | 运行时 |

---

## 七、与设计方案的差异汇总

### 7.1 技术选型差异（已在第二节标注 ⚠️ 的）

| # | 设计 | 实现 | 原因 |
|---|---|---|---|
| 1 | ChromaDB 向量库 | SimpleVectorStore（内存） | 新版 ChromaDB 废弃 v1 API；Docker 拉镜像慢 |
| 2 | Ollama 本地 embedding | SiliconFlow 在线 bge-m3（免费） | 模型下载慢；在线免费方案更轻 |
| 3 | Docker 编排 | 本地进程（start.sh） | Docker Hub 网络受限；全在线 API 后不需要本地服务 |
| 4 | Ant Design X Conversation | Bubble + Sender + ThoughtChain | 1.6.1 版本 API 变更，无 Conversation 组件 |
| 5 | 端口 8080 | 8081 | 本机 8080 被占用 |

### 7.2 功能实现差异

| # | 设计 | 实现 | 说明 |
|---|---|---|---|
| 1 | 收口工具由"画像集齐自动触发" | 由 **LLM 依据 prompt 指导自主决定** 调用 finalize_recommend | 行为一致，实现方式从"规则触发"改为"模型判断" |
| 2 | 长期记忆（跨会话） | 未实现 | demo 仅做会话内记忆；画像/历史按 session_id 存储，接口已预留 |
| 3 | 评估体系（Hit Rate/MRR 等） | 未实现 | demo 通过验收文档人工核验替代 |
| 4 | 流式逐字输出 | 回答为整段推送（answer 事件一次给全） | SSE 事件流已实现，未做 token 级流式 |
| 5 | 页面选数据"多个课程拖入" | 支持多选，请求携带 selectedData 列表 | 与设计一致 |

### 7.3 与需求原意的一致性

- ✅ 本地 SQLite 建库 + 课程体系（科目→课程→章节→单元→段落）
- ✅ 定价体系（单卖/套餐/包月/包年）
- ✅ 多轮问答精准匹配：约束累积、候选收窄、指代消解
- ✅ 工具调用全过程可视化（自己跟自己对话的"直播"）
- ✅ 页面选数据（单个/多个拖入）作为上下文
- ✅ 收口推荐 + 下单闭环
- ✅ 防幻觉：数字/事实均来自工具返回，未检索到明确拒绝

---

## 八、启动与使用

```bash
# 一键启动（后端 8081 + 前端 5173）
./start.sh
# 停止
./start.sh stop
```

配置（`.env`）：
```env
DEEPSEEK_API_KEY=sk-...            # 必填
DEEPSEEK_MODEL=deepseek-v4-flash
EMBEDDING_BASE_URL=https://api.siliconflow.cn   # 不带 /v1（Spring AI 自动拼接）
EMBEDDING_API_KEY=sk-...           # 必填（bge-m3 免费）
```

浏览器打开 **http://localhost:5173** 验收（对照 `验收文档_多轮智能问答Demo.md`）。

---

## 九、目录结构

```
agent_demo/
├── start.sh                  # 一键启动/停止
├── .env                      # API Key 配置（勿提交）
├── docker-compose.yml        # 可选的后端容器化方式（默认不用 Docker）
├── 调研报告_多轮智能问答开源项目.md
├── 方案设计_多轮智能问答课程推荐系统.md
├── 验收文档_多轮智能问答Demo.md
├── 实现说明文档_多轮智能问答Demo.md   # 本文档
├── backend/
│   ├── pom.xml               # Spring Boot 3.5.3 + Spring AI 1.1.6
│   ├── Dockerfile            # 可选容器化
│   ├── data/course.db        # SQLite（运行时生成）
│   └── src/main/java/com/demo/courserag/
│       ├── CourseRagApplication.java
│       ├── controller/ChatController.java      # /api/chat(SSE) /api/courses /api/packages
│       ├── service/AgentService.java           # 五步管线 + 工具循环
│       ├── service/ToolExecutor.java           # 7 工具 + FunctionToolCallback
│       ├── service/RetrievalService.java       # 向量检索 + 关键词兜底
│       ├── service/EmbeddingIndexer.java       # 启动向量化
│       ├── service/DatabaseInitializer.java    # 建表 + 种子数据
│       ├── repository/CourseRepository.java    # 业务查询（参数化 SQL）
│       ├── repository/SessionRepository.java   # 会话画像/历史
│       ├── config/VectorStoreConfig.java       # SimpleVectorStore Bean
│       └── model/                              # record 模型
└── frontend/
    ├── package.json          # React 18 + antd + @ant-design/x 1.6.1
    ├── vite.config.js        # 5173 → 代理 /api → 8081
    └── src/App.jsx           # 三栏布局（货架/对话/推理面板）+ SSE 解析
```

---

## 十、已知限制与后续可改进

| 项 | 现状 | 改进方向 |
|---|---|---|
| 向量库 | 内存 SimpleVectorStore | 数据量大时换 ChromaDB/Milvus/pgvector |
| 流式输出 | 整段推送 | 改用 token 级 SSE 流式 |
| 长期记忆 | 未实现 | 画像跨会话沉淀（参考腾讯 Agent Memory 四层架构） |
| 评估 | 人工验收 | 加 Hit Rate/MRR/Groundedness 自动化评估 |
| 收口触发 | LLM 自主判断 | 可加规则校验兜底（画像集齐强制收口） |
| 订单 | 模拟订单号 | 接真实库存/支付 |
| 安全 | 无鉴权 | 生产需加用户体系与限流 |

package com.demo.courserag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 启动时建表 + 写入种子数据（幂等） */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        createSchema();
        if (count("SELECT COUNT(*) FROM course") == 0) {
            seedData();
            log.info("已写入课程种子数据");
        } else {
            log.info("数据库已有数据，跳过种子写入");
        }
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void createSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS subject (
              id INTEGER PRIMARY KEY, name TEXT NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS course (
              id INTEGER PRIMARY KEY,
              subject_id INTEGER NOT NULL,
              name TEXT NOT NULL,
              textbook TEXT,
              grade TEXT,
              is_after_school INTEGER DEFAULT 0,
              duration_days INTEGER,
              description TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chapter (
              id INTEGER PRIMARY KEY,
              course_id INTEGER NOT NULL,
              seq INTEGER,
              title TEXT,
              summary TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS unit (
              id INTEGER PRIMARY KEY,
              chapter_id INTEGER NOT NULL,
              seq INTEGER,
              title TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS paragraph (
              id INTEGER PRIMARY KEY,
              unit_id INTEGER NOT NULL,
              seq INTEGER,
              content TEXT,
              summary TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS pricing (
              id INTEGER PRIMARY KEY,
              course_id INTEGER,
              type TEXT NOT NULL,
              price REAL NOT NULL,
              duration_days INTEGER
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS package (
              id INTEGER PRIMARY KEY,
              name TEXT NOT NULL,
              price REAL NOT NULL,
              duration_days INTEGER,
              description TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS package_course (
              package_id INTEGER NOT NULL,
              course_id INTEGER NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS session_state (
              id TEXT PRIMARY KEY,
              profile_json TEXT,
              history_json TEXT,
              summary TEXT,
              status TEXT DEFAULT 'collecting',
              updated_at TEXT DEFAULT (datetime('now'))
            )""");
    }

    private void seedData() {
        // ---- 科目 ----
        jdbc.update("INSERT INTO subject (id, name) VALUES (1, '英语')");

        // ---- 课程 ----
        insertCourse(1, 1, "自然拼读入门", "自研 Phonics 教材", "小学", 1, 30,
                "零基础英语启蒙，掌握 26 个字母发音与常见字母组合拼读规则，适合小学生第一次系统接触英语。");
        insertCourse(2, 1, "小学英语基础", "人教版 PEP 三起", "小学", 0, 60,
                "对应小学课内进度，覆盖词汇、句型、简单对话，与教材同步巩固。");
        insertCourse(3, 1, "新概念英语一册", "新概念英语青少版", "初中", 0, 90,
                "经典语法与句型训练，打下初中英语基础，覆盖时态、从句入门。");
        insertCourse(4, 1, "中考英语冲刺", "中考考点精讲", "初中", 0, 45,
                "针对中考高频考点：完形、阅读、写作，真题训练与解题技巧。");
        insertCourse(5, 1, "高考英语强化", "高考真题汇编", "高中", 0, 60,
                "高考题型专项突破，阅读理解、语法填空、写作高分模板。");
        insertCourse(6, 1, "雅思基础班", "剑桥雅思官方教材", "高中/成人", 1, 45,
                "零基础入门雅思听说读写四项，从听力题型、口语答题到写作模板，适合首次备考。");
        insertCourse(7, 1, "雅思速成班", "剑桥雅思官方教材", "高中/成人", 1, 28,
                "高强度短期冲刺，浓缩核心考点与应试技巧，适合时间紧张的考生。");
        insertCourse(8, 1, "托福强化班", "TOEFL iBT 官方指南", "高中/成人", 1, 60,
                "托福听说读写全科强化，学术场景词汇与独立写作模板。");
        insertCourse(9, 1, "商务英语", "BEC 商务英语教材", "成人", 1, 90,
                "职场商务场景英语：邮件、会议、谈判、汇报，提升职场英语应用能力。");
        insertCourse(10, 1, "口语情景对话", "自研情景口语", "全年级", 1, 30,
                "日常口语情景训练：点餐、问路、旅行、社交，开口练习为主。");

        // ---- 定价（单卖）----
        insertPricing(1, 1, "single", 999, 30);
        insertPricing(2, 2, "single", 1999, 60);
        insertPricing(3, 3, "single", 2999, 90);
        insertPricing(4, 4, "single", 2599, 45);
        insertPricing(5, 5, "single", 3499, 60);
        insertPricing(6, 6, "single", 1999, 45);
        insertPricing(7, 7, "single", 2599, 28);
        insertPricing(8, 8, "single", 3999, 60);
        insertPricing(9, 9, "single", 4599, 90);
        insertPricing(10, 10, "single", 1299, 30);

        // ---- 套餐 ----
        jdbc.update("INSERT INTO package (id, name, price, duration_days, description) VALUES (?,?,?,?,?)",
                1, "雅思全科套餐", 3499, 45, "雅思基础班 + 速成班 + 口语训练 + 全真模考，一站式备考");
        jdbc.update("INSERT INTO package (id, name, price, duration_days, description) VALUES (?,?,?,?,?)",
                2, "中考全科套餐", 3999, 60, "新概念英语一册 + 中考冲刺，系统提升到中考水平");
        jdbc.update("INSERT INTO package (id, name, price, duration_days, description) VALUES (?,?,?,?,?)",
                3, "小学英语综合包", 2599, 60, "自然拼读 + 小学英语基础，幼小衔接一站式");
        jdbc.update("INSERT INTO package (id, name, price, duration_days, description) VALUES (?,?,?,?,?)",
                4, "包月会员", 499, 30, "30 天内任意选择 2 门课程学习");
        jdbc.update("INSERT INTO package (id, name, price, duration_days, description) VALUES (?,?,?,?,?)",
                5, "包年会员", 4999, 365, "一年内全部课程无限学习");

        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (1, 6)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (1, 7)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (1, 10)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (2, 3)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (2, 4)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (3, 1)");
        jdbc.update("INSERT INTO package_course (package_id, course_id) VALUES (3, 2)");
        // 包月/包年不绑定具体课程，通过 package 表本身表达

        // ---- 章节/单元/段落（C6 雅思基础班全量 + 其他课程示例）----
        seedChaptersForCourse6();
        seedChaptersForCourse1();
        seedChaptersForCourse4();
        seedChaptersForCourse7();
        seedChaptersForCourse9();
        seedChaptersForCourse10();
    }

    private void insertCourse(int id, int subjectId, String name, String textbook, String grade,
                              int afterSchool, int days, String desc) {
        jdbc.update("INSERT INTO course (id, subject_id, name, textbook, grade, is_after_school, duration_days, description) VALUES (?,?,?,?,?,?,?,?)",
                id, subjectId, name, textbook, grade, afterSchool, days, desc);
    }

    private void insertPricing(int id, int courseId, String type, double price, int days) {
        jdbc.update("INSERT INTO pricing (id, course_id, type, price, duration_days) VALUES (?,?,?,?,?)",
                id, courseId, type, price, days);
    }

    private void insertChapter(int id, int courseId, int seq, String title, String summary) {
        jdbc.update("INSERT INTO chapter (id, course_id, seq, title, summary) VALUES (?,?,?,?,?)",
                id, courseId, seq, title, summary);
    }

    private void insertUnit(int id, int chapterId, int seq, String title) {
        jdbc.update("INSERT INTO unit (id, chapter_id, seq, title) VALUES (?,?,?,?)", id, chapterId, seq, title);
    }

    private void insertParagraph(int id, int unitId, int seq, String content, String summary) {
        jdbc.update("INSERT INTO paragraph (id, unit_id, seq, content, summary) VALUES (?,?,?,?,?)",
                id, unitId, seq, content, summary);
    }

    /** C6 雅思基础班：4 章 × 2 单元（对照验收文档 2.4） */
    private void seedChaptersForCourse6() {
        insertChapter(1, 6, 1, "第 1 章 听力入门", "雅思听力题型结构、高频场景词汇");
        insertUnit(1, 1, 1, "单元 1");
        insertParagraph(1, 1, 1,
                "雅思听力题型结构：共四个部分、40 题、约 30 分钟答题时间。第一部分为日常生活对话，第二部分为独白，第三部分为多人学术讨论，第四部分为学术讲座。",
                "听力四部分结构与题量");
        insertParagraph(2, 1, 2,
                "听力高频场景词汇：校园场景（图书馆、选课、宿舍）、租房场景（押金、水电费）、旅游场景、学术讨论场景。掌握这些场景词是提分关键。",
                "高频场景词汇分类");
        insertUnit(2, 1, 2, "单元 2");
        insertParagraph(3, 2, 1,
                "听力答题技巧：先读题预判答案类型（数字、地点、人名），注意同义替换陷阱，拼写必须准确。",
                "听力预判与同义替换");

        insertChapter(2, 6, 2, "第 2 章 口语突破", "口语 Part 1/Part 2 答题技巧");
        insertUnit(3, 2, 1, "单元 1");
        insertParagraph(4, 3, 1,
                "口语 Part 1 答题技巧：针对自我介绍与日常话题，如学习、家乡、爱好。答案保持 2-3 句，先直接回答再展开理由。",
                "Part 1 日常话题答题结构");
        insertUnit(4, 2, 2, "单元 2");
        insertParagraph(5, 4, 1,
                "口语 Part 2 卡片题：一分钟准备，组织话题要点。可采用「观点-例子-细节」的三步结构，避免停顿。",
                "Part 2 卡片题组织方法");

        insertChapter(3, 6, 3, "第 3 章 阅读提速", "阅读题型与同义替换识别");
        insertUnit(5, 3, 1, "单元 1");
        insertParagraph(6, 5, 1,
                "阅读题型：判断题（TRUE/FALSE/NOT GIVEN）、匹配题、填空题。判断题注意区分 FALSE 与 NOT GIVEN，答案定位要准确。",
                "阅读三大题型解题策略");
        insertUnit(6, 3, 2, "单元 2");
        insertParagraph(7, 6, 1,
                "同义替换识别：阅读提分的核心能力。原文与题目往往用不同词汇表达同一意思，识别同义替换可直接定位答案。",
                "同义替换识别方法");

        insertChapter(4, 6, 4, "第 4 章 写作精讲", "小作文图表描述与大作文论证");
        insertUnit(7, 4, 1, "单元 1");
        insertParagraph(8, 7, 1,
                "小作文：图表描述模板。开头概述趋势，中间分段描述数据变化，使用比较级与趋势词汇（上升、下降、保持平稳）。",
                "小作文图表描述模板");
        insertUnit(8, 4, 2, "单元 2");
        insertParagraph(9, 8, 1,
                "大作文 Task 2 论证结构：观点-论据-例证。开头亮明立场，主体段每个论点配一个例子，结尾总结升华。",
                "大作文论证结构");
    }

    /** C1 自然拼读入门 */
    private void seedChaptersForCourse1() {
        insertChapter(5, 1, 1, "字母与发音", "26 个字母发音");
        insertUnit(9, 5, 1, "单元 1");
        insertParagraph(10, 9, 1,
                "26 个英文字母的标准发音与书写，元音字母 A/E/I/O/U 的短音与长音区别。",
                "字母发音基础");
        insertChapter(6, 1, 2, "字母组合拼读", "常见组合发音规则");
        insertUnit(10, 6, 1, "单元 1");
        insertParagraph(11, 10, 1,
                "常见字母组合拼读规则：sh/ch/th/ee/oo 等组合的发音规律，帮助孩子见词能读。",
                "字母组合拼读规则");
    }

    /** C4 中考英语冲刺 */
    private void seedChaptersForCourse4() {
        insertChapter(7, 4, 1, "完形填空技巧", "高频考点与上下文逻辑");
        insertUnit(11, 7, 1, "单元 1");
        insertParagraph(12, 11, 1,
                "完形填空解题策略：先通读把握大意，再结合上下文逻辑推断选项，重点考察动词时态与固定搭配。",
                "完形填空解题策略");
        insertChapter(8, 4, 2, "书面表达", "中考作文模板");
        insertUnit(12, 8, 1, "单元 1");
        insertParagraph(13, 12, 1,
                "中考书面表达高分模板：开头引入话题，中间分点论述，结尾总结观点，注意使用连接词提升连贯性。",
                "中考作文模板结构");
    }

    /** C7 雅思速成班 */
    private void seedChaptersForCourse7() {
        insertChapter(9, 7, 1, "核心考点浓缩", "高频考点速记");
        insertUnit(13, 9, 1, "单元 1");
        insertParagraph(14, 13, 1,
                "雅思高频考点浓缩：听力高频词汇、阅读定位技巧、写作常见模板与口语高频话题速记。",
                "高频考点速记");
        insertChapter(10, 7, 2, "模考冲刺", "全真模考与错题分析");
        insertUnit(14, 10, 1, "单元 1");
        insertParagraph(15, 14, 1,
                "全真模拟考试与错题分析：按照真实考试节奏完成套题，针对错题归类复盘，查漏补缺。",
                "模考与错题复盘");
    }

    /** C9 商务英语 */
    private void seedChaptersForCourse9() {
        insertChapter(11, 9, 1, "商务邮件与会议", "职场书面与口头表达");
        insertUnit(15, 11, 1, "单元 1");
        insertParagraph(16, 15, 1,
                "商务邮件写作要点：主题明确、开头寒暄、正文要点分条、结尾礼貌用语；会议中常用表达与主持话术。",
                "商务邮件与会议表达");
        insertChapter(12, 9, 2, "商务谈判", "谈判话术与技巧");
        insertUnit(16, 12, 1, "单元 1");
        insertParagraph(17, 16, 1,
                "商务谈判常用话术：提出报价、讨价还价、达成共识的英文表达，以及谈判中的礼貌拒绝技巧。",
                "商务谈判话术");
    }

    /** C10 口语情景对话 */
    private void seedChaptersForCourse10() {
        insertChapter(13, 10, 1, "日常情景口语", "点餐、问路、旅行");
        insertUnit(17, 13, 1, "单元 1");
        insertParagraph(18, 17, 1,
                "日常情景口语：点餐常用表达、问路与指路句式、旅行场景（订房、登机、购物）实用口语。",
                "日常情景口语表达");
    }
}

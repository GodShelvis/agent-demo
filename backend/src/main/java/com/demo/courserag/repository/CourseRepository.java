package com.demo.courserag.repository;

import com.demo.courserag.model.CourseBrief;
import com.demo.courserag.model.PackageInfo;
import com.demo.courserag.model.ParagraphDoc;
import com.demo.courserag.model.PricingInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 课程体系数据访问（SQLite） */
@Repository
public class CourseRepository {

    private final JdbcTemplate jdbc;

    public CourseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部课程 + 单卖价（前端左侧列表） */
    public List<CourseBrief> findAllCourses() {
        return jdbc.query("""
            SELECT c.id, c.name, c.textbook, c.grade, c.is_after_school, c.duration_days, c.description,
                   COALESCE((SELECT price FROM pricing WHERE course_id = c.id AND type='single'), 0) AS price
            FROM course c ORDER BY c.id
            """, (rs, i) -> new CourseBrief(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("textbook"),
                rs.getString("grade"),
                rs.getInt("is_after_school") == 1,
                rs.getInt("duration_days"),
                rs.getString("description"),
                rs.getDouble("price")));
    }

    public CourseBrief findCourseById(int id) {
        return findAllCourses().stream().filter(c -> c.id() == id).findFirst().orElse(null);
    }

    public CourseBrief findCourseByName(String name) {
        if (name == null || name.isBlank()) return null;
        return findAllCourses().stream()
                .filter(c -> c.name().contains(name.trim()) || name.trim().contains(c.name()))
                .findFirst()
                .orElse(null);
    }

    /** 按约束过滤课程候选（收窄核心） */
    public List<CourseBrief> searchCourses(String subject, String level, String grade,
                                           Boolean afterSchool, Integer durationMaxDays, Double budgetMax) {
        return findAllCourses().stream()
                .filter(c -> subject == null || subject.isBlank()
                        || c.name().contains(subject) || c.grade().contains(subject))
                .filter(c -> grade == null || grade.isBlank() || c.grade().contains(grade))
                .filter(c -> afterSchool == null || c.isAfterSchool() == afterSchool)
                .filter(c -> durationMaxDays == null || c.durationDays() <= durationMaxDays)
                .filter(c -> budgetMax == null || c.price() <= budgetMax)
                .toList();
    }

    public List<PricingInfo> findPricingByCourse(int courseId) {
        return jdbc.query("SELECT type, price, duration_days FROM pricing WHERE course_id = ?",
                (rs, i) -> new PricingInfo(rs.getString("type"), rs.getDouble("price"), rs.getInt("duration_days")),
                courseId);
    }

    public List<PackageInfo> findAllPackages() {
        // 注意：不能在 RowMapper 内嵌套查询（单连接池会死锁），先查套餐再逐个补课程名
        List<PackageInfo> packages = jdbc.query(
                "SELECT id, name, price, duration_days, description FROM package ORDER BY id",
                (rs, i) -> new PackageInfo(rs.getInt("id"), rs.getString("name"), rs.getDouble("price"),
                        rs.getInt("duration_days"), rs.getString("description"), List.of()));
        return packages.stream().map(p -> {
            List<String> courses = jdbc.queryForList(
                    "SELECT c.name FROM package_course pc JOIN course c ON c.id = pc.course_id WHERE pc.package_id = ?",
                    String.class, p.id());
            return new PackageInfo(p.id(), p.name(), p.price(), p.durationDays(), p.description(), courses);
        }).toList();
    }

    /** 全部段落（用于向量索引） */
    public List<ParagraphDoc> findAllParagraphs() {
        return jdbc.query("""
            SELECT p.id, c.id AS cid, c.name AS cname, ch.title AS chtitle, u.title AS utitle, p.content
            FROM paragraph p
            JOIN unit u ON u.id = p.unit_id
            JOIN chapter ch ON ch.id = u.chapter_id
            JOIN course c ON c.id = ch.course_id
            ORDER BY p.id
            """, (rs, i) -> new ParagraphDoc(
                rs.getInt("id"),
                rs.getInt("cid"),
                rs.getString("cname"),
                rs.getString("chtitle"),
                rs.getString("utitle"),
                rs.getString("content")));
    }

    /** 按课程名找段落（内容检索的直接过滤，供向量检索结果补充） */
    public List<ParagraphDoc> findParagraphsByCourseName(String courseName) {
        return findAllParagraphs().stream()
                .filter(p -> courseName != null && p.courseName().contains(courseName))
                .toList();
    }
}

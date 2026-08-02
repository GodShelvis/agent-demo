package com.demo.courserag.model;

/** 课程摘要（前端列表 + 推荐用） */
public record CourseBrief(
        int id,
        String name,
        String textbook,
        String grade,
        boolean isAfterSchool,
        int durationDays,
        String description,
        double price
) {}

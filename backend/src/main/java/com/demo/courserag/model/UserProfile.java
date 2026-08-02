package com.demo.courserag.model;

import java.util.List;

/** 需求画像（会话状态） */
public class UserProfile {
    public String targetSubject;       // 想学的科目
    public String targetCourse;        // 目标课程名
    public String level;               // 水平：零基础/初级/中级
    public String grade;               // 年级：小学/初中/高中/成人
    public boolean hasLevel;           // 标记 level 是否已收集
    public boolean hasTimeLimit;
    public Integer timeLimitDays;      // 时间约束（天）
    public boolean hasBudget;
    public Double budget;              // 预算
    public String learningGoal;        // 学习目标
    public List<String> recommended;   // 已推荐课程

    public static UserProfile empty() {
        UserProfile p = new UserProfile();
        p.targetSubject = "";
        p.targetCourse = "";
        p.level = "";
        p.grade = "";
        p.learningGoal = "";
        p.recommended = new java.util.ArrayList<>();
        return p;
    }

    /** 关键约束是否集齐（目标 + 水平 + 时间），决定是否收口 */
    public boolean readyForFinalize() {
        boolean hasTarget = (targetCourse != null && !targetCourse.isBlank())
                || (targetSubject != null && !targetSubject.isBlank());
        return hasTarget && hasLevel && hasTimeLimit;
    }
}

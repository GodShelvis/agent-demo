package com.demo.courserag.model;

/** 套餐信息 */
public record PackageInfo(
        int id,
        String name,
        double price,
        int durationDays,
        String description,
        java.util.List<String> courseNames
) {}

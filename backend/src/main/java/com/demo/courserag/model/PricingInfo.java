package com.demo.courserag.model;

/** 定价信息（单卖/套餐/包月/包年） */
public record PricingInfo(
        String type,
        double price,
        int durationDays
) {}

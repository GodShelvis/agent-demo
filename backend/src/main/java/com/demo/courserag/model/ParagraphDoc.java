package com.demo.courserag.model;

/** 段落文档（向量检索用，带来源上卷信息） */
public record ParagraphDoc(
        int id,
        int courseId,
        String courseName,
        String chapterTitle,
        String unitTitle,
        String content
) {
    /** 用于 Embedding 的文本拼接 */
    public String embeddingText() {
        return "课程：" + courseName + "，章节：" + chapterTitle
                + "，单元：" + unitTitle + "。内容：" + content;
    }
}

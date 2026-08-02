package com.demo.courserag.service;

import com.demo.courserag.model.ParagraphDoc;
import com.demo.courserag.repository.CourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 内容检索：向量为主，关键词兜底 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private final VectorStore vectorStore;
    private final CourseRepository courseRepository;
    private final int topK;

    public RetrievalService(VectorStore vectorStore, CourseRepository courseRepository,
                            @Value("${app.retrieval-top-k:5}") int topK) {
        this.vectorStore = vectorStore;
        this.courseRepository = courseRepository;
        this.topK = topK;
    }

    /** 检索课程内容段落（可限定课程） */
    public List<ParagraphDoc> searchContent(String query, String courseName) {
        List<ParagraphDoc> results = new ArrayList<>();
        try {
            var searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(searchRequest);
            for (Document doc : docs) {
                String cname = (String) doc.getMetadata().getOrDefault("courseName", "");
                String cht = (String) doc.getMetadata().getOrDefault("chapterTitle", "");
                String ut = (String) doc.getMetadata().getOrDefault("unitTitle", "");
                int cid = ((Number) doc.getMetadata().getOrDefault("courseId", 0)).intValue();
                int pid = ((Number) doc.getMetadata().getOrDefault("paragraphId", 0)).intValue();
                if (courseName != null && !courseName.isBlank() && !cname.contains(courseName)) {
                    continue;
                }
                results.add(new ParagraphDoc(pid, cid, cname, cht, ut, doc.getText()));
            }
        } catch (Exception e) {
            log.warn("向量检索失败，回退关键词: {}", e.getMessage());
        }
        // 向量结果不足时用关键词兜底
        if (results.isEmpty() && courseName != null && !courseName.isBlank()) {
            results.addAll(courseRepository.findParagraphsByCourseName(courseName));
        }
        return results.stream().limit(topK).toList();
    }
}

package com.demo.courserag.service;

import com.demo.courserag.model.ParagraphDoc;
import com.demo.courserag.repository.CourseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 启动时把课程段落写入向量库（幂等：doc id 固定，重复 add 会覆盖） */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.enable-indexer", havingValue = "true", matchIfMissing = true)
public class EmbeddingIndexer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndexer.class);
    private final VectorStore vectorStore;
    private final CourseRepository repo;

    public EmbeddingIndexer(VectorStore vectorStore, CourseRepository repo) {
        this.vectorStore = vectorStore;
        this.repo = repo;
    }

    @Override
    public void run(String... args) {
        try {
            List<ParagraphDoc> paragraphs = repo.findAllParagraphs();
            if (paragraphs.isEmpty()) {
                log.info("没有段落需要索引");
                return;
            }
            List<Document> docs = paragraphs.stream().map(p -> {
                Map<String, Object> meta = new HashMap<>();
                meta.put("paragraphId", p.id());
                meta.put("courseId", p.courseId());
                meta.put("courseName", p.courseName());
                meta.put("chapterTitle", p.chapterTitle());
                meta.put("unitTitle", p.unitTitle());
                return new Document("paragraph-" + p.id(), p.embeddingText(), meta);
            }).toList();
            vectorStore.add(docs);
            log.info("已向量化 {} 个段落并写入向量库", docs.size());
        } catch (Exception e) {
            log.error("向量索引失败（稍后可通过重启重试）: {}", e.getMessage());
        }
    }
}

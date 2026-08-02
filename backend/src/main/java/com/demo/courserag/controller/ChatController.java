package com.demo.courserag.controller;

import com.demo.courserag.model.ChatRequest;
import com.demo.courserag.model.CourseBrief;
import com.demo.courserag.model.PackageInfo;
import com.demo.courserag.repository.CourseRepository;
import com.demo.courserag.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 前端接口：课程列表 + 会话 + SSE 对话流 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final AgentService agent;
    private final CourseRepository repo;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(AgentService agent, CourseRepository repo) {
        this.agent = agent;
        this.repo = repo;
    }

    /** 课程列表（前端左侧） */
    @GetMapping("/courses")
    public List<CourseBrief> courses() {
        return repo.findAllCourses();
    }

    /** 套餐列表 */
    @GetMapping("/packages")
    public List<PackageInfo> packages() {
        return repo.findAllPackages();
    }

    /** 多轮对话（SSE 流式：thinking / tool_start / tool_end / answer / done） */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            AgentService.EventSink sink = (event, data) -> {
                try {
                    emitter.send(SseEmitter.event().name(event).data(data));
                } catch (IOException e) {
                    log.warn("SSE 推送失败: {}", e.getMessage());
                }
            };
            try {
                agent.handleTurn(req, sink);
                emitter.complete();
            } catch (Exception e) {
                log.error("对话处理异常", e);
                try {
                    emitter.send(SseEmitter.event().name("error").data("服务内部错误：" + e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.complete();
            }
        });
        return emitter;
    }
}

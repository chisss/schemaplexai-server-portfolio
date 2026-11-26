package com.schemaplexai.service.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 检索侧线程池。
 *
 * <p>BM25 与向量召回并行执行，池子必须与模型推理池隔离：
 * 检索被堵死时不能连带把生成链路也拖垮。核心线程给足（召回是 IO 密集），
 * 队列有界防止雪崩时任务无限堆积。
 */
@Slf4j
@Configuration
public class RagRetrievalConfig {

    @Bean("ragRetrievalExecutor")
    public Executor ragRetrievalExecutor() {
        AtomicInteger counter = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                8, 16,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "rag-retrieve-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        log.info("RAG 检索线程池初始化: core=8, max=16, queue=256");
        return executor;
    }
}

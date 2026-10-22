package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

// **开启 Spring 的 @Async 异步方法代理。**
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "interviewPreparationTaskExecutor")
    public Executor interviewPreparationTaskExecutor() {
        return executor("interview-preparation-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "questionGenerationTaskExecutor")
    public Executor questionGenerationTaskExecutor() {
        // **预生成任务丢了也无所谓，静默丢弃，不阻塞调用方。**
        return executor("question-generation-", 2, 4, 50,
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean(name = "resumeParsingTaskExecutor")
    public Executor resumeParsingTaskExecutor() {
        return executor("resume-parsing-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "aiScoringTaskExecutor")
    public Executor aiScoringTaskExecutor() {
        return executor("ai-scoring-", 4, 8, 100,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "followUpTaskExecutor")
    public Executor followUpTaskExecutor() {
        return executor("follow-up-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "reportGenerationTaskExecutor")
    public Executor reportGenerationTaskExecutor() {
        return executor("report-generation-", 2, 4, 30,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "demeanorAnalysisTaskExecutor")
    public Executor demeanorAnalysisTaskExecutor() {
        return executor("demeanor-analysis-", 2, 4, 30,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "transcriptionTaskExecutor")
    public Executor transcriptionTaskExecutor() {
        return executor("transcription-", 2, 4, 100,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "cacheWriteTaskExecutor")
    public Executor cacheWriteTaskExecutor() {
        // **缓存回填等轻量异步操作，不依赖 @Async 注解，供 CompletableFuture 使用。**
        return executor("cache-write-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private Executor executor(
            String prefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            RejectedExecutionHandler rejectionPolicy
    ) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        // 拒绝策略：默认用 CallerRunsPolicy（谁提交谁执行，任务不丢）
        executor.setRejectedExecutionHandler(rejectionPolicy);
        executor.initialize();
        return executor;
    }
}
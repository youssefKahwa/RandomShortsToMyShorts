package com.rstms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /** One thread per concurrently-running job (job.process blocks here until all its own work is done). */
    @Bean(name = "jobExecutor")
    public Executor jobExecutor(AppProperties props) {
        int concurrency = Math.max(1, props.getPipeline().getMaxConcurrentJobs());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("job-");
        executor.initialize();
        return executor;
    }

    /**
     * Separate pool for the per-platform render fan-out within a job. Must be independent from
     * jobExecutor: a job thread blocks (join) waiting on its own render tasks, so sharing one pool
     * between "jobs" and "renders" can starve every thread on blocked joins with no thread left to
     * actually run a render - a real deadlock once concurrent jobs >= jobExecutor's pool size.
     */
    @Bean(name = "renderExecutor")
    public Executor renderExecutor(AppProperties props) {
        int concurrency = Math.max(3, props.getPipeline().getMaxConcurrentJobs() * 3);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("render-");
        executor.initialize();
        return executor;
    }
}

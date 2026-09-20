package com.rstms.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Hands a job stage off to the bounded jobExecutor pool so the triggering HTTP request returns immediately. */
@Component
public class JobQueueService {

    private final JobProcessor processor;

    public JobQueueService(JobProcessor processor) {
        this.processor = processor;
    }

    @Async("jobExecutor")
    public void submit(String jobId) {
        processor.compose(jobId);
    }

    @Async("jobExecutor")
    public void export(String jobId) {
        processor.export(jobId);
    }
}

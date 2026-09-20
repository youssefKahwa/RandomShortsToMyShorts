package com.rstms.model;

/** QUEUED -> PROCESSING (compose) -> REVIEW (preview the master) -> PROCESSING (export) -> DONE, or FAILED at any point. */
public enum JobStatus { QUEUED, PROCESSING, REVIEW, DONE, FAILED }

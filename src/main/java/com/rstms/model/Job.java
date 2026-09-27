package com.rstms.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persisted as job.json under data/jobs/{id}/ - the whole state of one localization run. */
public class Job {
    private String id;
    private String label;
    private JobStatus status = JobStatus.QUEUED;
    private String stage = "queued";
    private String sourceVideoName;
    /** For a "green_screen_overlay" job: "green"/"blue"/"red" -> the overlay clip composited onto
     * that colored screen area. "green" is mandatory when this style is used; the others optional. */
    private Map<String, String> overlayVideoNames = new LinkedHashMap<>();
    /** elementId (from an "Incrustations" overlayElements entry) -> saved PNG filename, for
     * image-type overlay elements. Available regardless of videoStyle. */
    private Map<String, String> overlayImageNames = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;
    private List<String> warnings = new ArrayList<>();
    private String error;
    private List<String> platforms = new ArrayList<>();
    /** platform -> output filename under output/ */
    private Map<String, String> outputFiles = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus v) { this.status = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { this.stage = v; }
    public String getSourceVideoName() { return sourceVideoName; }
    public void setSourceVideoName(String v) { this.sourceVideoName = v; }
    public Map<String, String> getOverlayVideoNames() { return overlayVideoNames; }
    public void setOverlayVideoNames(Map<String, String> v) { this.overlayVideoNames = v; }
    public Map<String, String> getOverlayImageNames() { return overlayImageNames; }
    public void setOverlayImageNames(Map<String, String> v) { this.overlayImageNames = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> v) { this.warnings = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public List<String> getPlatforms() { return platforms; }
    public void setPlatforms(List<String> v) { this.platforms = v; }
    public Map<String, String> getOutputFiles() { return outputFiles; }
    public void setOutputFiles(Map<String, String> v) { this.outputFiles = v; }
}

package com.rstms.model;

/** One entry in the bundled, hand-verified voice catalog (see voices-catalog.json). */
public class VoiceInfo {
    private String id;
    private String label;
    private String gender;
    private String quality;
    private String dataset;
    private String license;
    private boolean commercialSafe;
    private String sampleFile;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getQuality() { return quality; }
    public void setQuality(String v) { this.quality = v; }
    public String getDataset() { return dataset; }
    public void setDataset(String v) { this.dataset = v; }
    public String getLicense() { return license; }
    public void setLicense(String v) { this.license = v; }
    public boolean isCommercialSafe() { return commercialSafe; }
    public void setCommercialSafe(boolean v) { this.commercialSafe = v; }
    public String getSampleFile() { return sampleFile; }
    public void setSampleFile(String v) { this.sampleFile = v; }
}

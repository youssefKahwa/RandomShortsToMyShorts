package com.rstms.model;

/** A background music bed, looped and trimmed to the full video length, mixed in low under everything. */
public class MusicSpec {
    /** Filename under the shared assets/music/ library - upload it once on the Assets page. */
    private String file;
    private double volume = 0.15;

    public String getFile() { return file; }
    public void setFile(String v) { this.file = v; }
    public double getVolume() { return volume; }
    public void setVolume(double v) { this.volume = v; }
}

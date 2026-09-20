package com.rstms.model;

/** A one-shot sound effect mixed in on top of everything else at a single timestamp. */
public class SoundEffectSpec {
    private double start;
    /** Filename under the shared assets/sfx/ library - upload it once on the Assets page. */
    private String file;
    private double volume = 1.0;

    public double getStart() { return start; }
    public void setStart(double v) { this.start = v; }
    public String getFile() { return file; }
    public void setFile(String v) { this.file = v; }
    public double getVolume() { return volume; }
    public void setVolume(double v) { this.volume = v; }
}

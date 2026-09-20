package com.rstms.model;

/**
 * Chroma-key settings for compositing an overlay video onto the green-screen region of the source
 * (base) video. All fields are optional - the defaults key out standard bright green without any
 * tuning, so most jobs never need to set this at all.
 */
public class GreenScreenSpec {
    private String color = "0x00FF00";
    private double similarity = 0.18;
    private double blend = 0.06;

    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
    public double getSimilarity() { return similarity; }
    public void setSimilarity(double v) { this.similarity = v; }
    public double getBlend() { return blend; }
    public void setBlend(double v) { this.blend = v; }
}

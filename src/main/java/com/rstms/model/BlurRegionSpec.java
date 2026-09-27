package com.rstms.model;

/** Blurs a rectangular region (logo, watermark, face) for a time range. Coordinates are pixels in the source video. */
public class BlurRegionSpec {
    private double start;
    // Boxed (not a primitive double) so a JSON "end": null - e.g. a raw instructions.json hand-edited
    // via the job page's "Modifier et relancer" textarea, or an empty end field on the advanced-JSON
    // screen - is visible as an actual null to JobValidator instead of Jackson silently defaulting a
    // primitive to 0.0, which produced a confusing "0.0-0.0" error indistinguishable from a real
    // zero-length zone.
    private Double end;
    private int x;
    private int y;
    private int width;
    private int height;
    private int strength = 15;

    public double getStart() { return start; }
    public void setStart(double v) { this.start = v; }
    public Double getEnd() { return end; }
    public void setEnd(Double v) { this.end = v; }
    public int getX() { return x; }
    public void setX(int v) { this.x = v; }
    public int getY() { return y; }
    public void setY(int v) { this.y = v; }
    public int getWidth() { return width; }
    public void setWidth(int v) { this.width = v; }
    public int getHeight() { return height; }
    public void setHeight(int v) { this.height = v; }
    public int getStrength() { return strength; }
    public void setStrength(int v) { this.strength = v; }
}

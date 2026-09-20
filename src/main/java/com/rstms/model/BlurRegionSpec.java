package com.rstms.model;

/** Blurs a rectangular region (logo, watermark, face) for a time range. Coordinates are pixels in the source video. */
public class BlurRegionSpec {
    private double start;
    private double end;
    private int x;
    private int y;
    private int width;
    private int height;
    private int strength = 15;

    public double getStart() { return start; }
    public void setStart(double v) { this.start = v; }
    public double getEnd() { return end; }
    public void setEnd(double v) { this.end = v; }
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

package com.rstms.model;

/**
 * One manual text or PNG overlay element ("Incrustations" step) - unrelated to the automatic
 * burned-in dialogue captions (see CaptionWriter / the "captions" flag). Placed as a plain
 * axis-aligned rectangle (no perspective warp, no rotation), same flat-geometry-plus-time-window
 * shape as BlurRegionSpec rather than the quadrilateral ScreenRegionSpec uses.
 */
public class OverlayElementSpec {
    private String type;        // "text" | "image"
    private String id;          // client-generated; ties an "image" element to its uploaded PNG
    private double start = 0;
    private double end = 0;     // 0 = until end of video, resolved against videoDuration at compose time
    private int x;
    private int y;
    private int width;
    private int height;
    private String text;        // text elements only
    private String stylePreset; // "bold_outline" | "highlight_box" | "bottom_banner" | "neon_glow"
    private int fontSize = 42;
    private String color;       // "#RRGGBB", the preset's quick-tweak base color

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
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
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getStylePreset() { return stylePreset; }
    public void setStylePreset(String v) { this.stylePreset = v; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int v) { this.fontSize = v; }
    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
}

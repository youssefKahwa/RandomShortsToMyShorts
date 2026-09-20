package com.rstms.model;

/**
 * The four corners of one colored chroma-key screen within the base video, in clockwise order
 * (top-left, top-right, bottom-right, bottom-left). A plain axis-aligned rectangle is just the
 * special case where all four corners line up - the general case lets a screen filmed at an angle
 * (not a straight-on 0/90 shot) be traced exactly as it actually appears, corner by corner. The
 * overlay is perspective-warped to fit this exact quadrilateral rather than stretched into a box.
 * Coordinates are relative to the base video's own frame, which is never resized or reshaped.
 */
public class ScreenRegionSpec {
    private Corner topLeft;
    private Corner topRight;
    private Corner bottomRight;
    private Corner bottomLeft;

    public static class Corner {
        private double x;
        private double y;

        public Corner() { }
        public Corner(double x, double y) { this.x = x; this.y = y; }

        public double getX() { return x; }
        public void setX(double v) { this.x = v; }
        public double getY() { return y; }
        public void setY(double v) { this.y = v; }
    }

    public Corner getTopLeft() { return topLeft; }
    public void setTopLeft(Corner v) { this.topLeft = v; }
    public Corner getTopRight() { return topRight; }
    public void setTopRight(Corner v) { this.topRight = v; }
    public Corner getBottomRight() { return bottomRight; }
    public void setBottomRight(Corner v) { this.bottomRight = v; }
    public Corner getBottomLeft() { return bottomLeft; }
    public void setBottomLeft(Corner v) { this.bottomLeft = v; }
}

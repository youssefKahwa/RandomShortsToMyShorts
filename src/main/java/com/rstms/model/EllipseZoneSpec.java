package com.rstms.model;

/**
 * A round (circle or oval) "no-light" zone for the ambient TV light - see LightingEffectSpec
 * .ellipseOccluders. Axis-aligned ellipse: centre (cx, cy) and radii (rx, ry), in the same
 * original-unrotated coordinate convention as JobRequest.screenRegions.
 */
public class EllipseZoneSpec {
    private double cx;
    private double cy;
    private double rx;
    private double ry;

    public EllipseZoneSpec() { }
    public EllipseZoneSpec(double cx, double cy, double rx, double ry) {
        this.cx = cx; this.cy = cy; this.rx = rx; this.ry = ry;
    }

    public double getCx() { return cx; }
    public void setCx(double v) { this.cx = v; }
    public double getCy() { return cy; }
    public void setCy(double v) { this.cy = v; }
    public double getRx() { return rx; }
    public void setRx(double v) { this.rx = v; }
    public double getRy() { return ry; }
    public void setRy(double v) { this.ry = v; }
}

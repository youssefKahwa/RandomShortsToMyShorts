package com.rstms.model;

/**
 * Chroma-key tuning for one screen ("green"/"blue"/"red") of a "green_screen_overlay" job. All
 * fields are optional - the defaults key out that screen's standard pure color without any tuning,
 * so most jobs never need to set this at all. Unlike the old green-only GreenScreenSpec, this same
 * shape applies uniformly to every screen color.
 */
public class ChromaKeySpec {
    private String color;
    private double similarity = 0.18;
    private double blend = 0.06;
    /** despill mix (0 = off). ffmpeg's despill filter only supports green/blue - has no effect,
     * and is not offered in the UI, for a red screen. Defaults to a nonzero value deliberately, and
     * this default matters more than edgeMargin's: on real (compressed, not-studio-lit) footage,
     * the pixels right at the true screen/bezel boundary are typically a genuine blend of key color
     * and background from lens/compression blur - too far from pure key color for chromakey's own
     * similarity threshold to remove (raising similarity to catch them risks eating into the
     * overlay's own true edge instead), but still visibly green-tinted. despill targets exactly
     * these already-opaque, nearly-but-not-quite-keyed pixels and strips the excess green - verified
     * empirically against real footage: with despill off, a thin green line was clearly visible at
     * the screen edge regardless of blend; enabling despill alone (blend unchanged) removed it
     * completely, while raising blend alone did not. */
    private double despill = 0.25;
    /** Percent (0-15) by which the composited region is grown past the traced corners, scaled
     * outward from the region's own center, before cropping/keying/warping. Defaults to a nonzero
     * value deliberately: a hand-traced region is almost always a pixel or few short of the real
     * screen edge, leaving a sliver of untouched, fully-saturated key color at the boundary in the
     * final render - a dead giveaway that it's a composite. Growing the composited area is
     * self-correcting rather than a guess: chromakey only ever reveals the overlay where a pixel
     * actually IS the key color, so any part of the extra margin that lands on real (non-key-color)
     * footage - a TV bezel, the wall around it - simply stays covered by that real footage instead
     * of showing overlay content, which is exactly the wanted behavior. */
    private double edgeMargin = 3.0;

    public String getColor() { return color; }
    public void setColor(String v) { this.color = v; }
    public double getSimilarity() { return similarity; }
    public void setSimilarity(double v) { this.similarity = v; }
    public double getBlend() { return blend; }
    public void setBlend(double v) { this.blend = v; }
    public double getDespill() { return despill; }
    public void setDespill(double v) { this.despill = v; }
    public double getEdgeMargin() { return edgeMargin; }
    public void setEdgeMargin(double v) { this.edgeMargin = v; }
}

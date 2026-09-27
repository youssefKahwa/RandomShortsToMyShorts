package com.rstms.model;

/**
 * Final whole-frame finishing pass, applied after every other effect (screens, blur, stickers,
 * captions) - unlike those, this always touches the entire frame, never a region. Two independent
 * parts: an optional "look" preset (grain/color/vignette combos meant to make a composite read as
 * one consistently-filmed shot rather than an obvious edit) and an optional quality enhance
 * (denoise + sharpen + a small contrast/saturation lift). Available for every videoStyle.
 */
public class FinishingSpec {
    /** null/"none" | "match_grain" | "cinematic" | "handheld_vignette". */
    private String lookPreset;
    private boolean enhance = false;
    /** 0.0-1.0 - how strong the enhance pass is; only meaningful when enhance is true. */
    private double enhanceIntensity = 0.5;

    public String getLookPreset() { return lookPreset; }
    public void setLookPreset(String v) { this.lookPreset = v; }
    public boolean isEnhance() { return enhance; }
    public void setEnhance(boolean v) { this.enhance = v; }
    public double getEnhanceIntensity() { return enhanceIntensity; }
    public void setEnhanceIntensity(double v) { this.enhanceIntensity = v; }
}

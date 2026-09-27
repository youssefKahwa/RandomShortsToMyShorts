package com.rstms.model;

/**
 * "Rendu TV" for one screen ("green"/"blue"/"red"): filters that make the overlay video look like it
 * is really being shown on the TV and filmed by the camera, instead of pasted-in digital pixels.
 * Applied only to that screen's overlay, before it is keyed into the green area - so, like the video
 * itself, they show only where the TV is green and never touch the room. Opt-in (enabled=false adds
 * zero filter-graph nodes); the other defaults are the wizard's "Naturel" preset. The math lives in
 * com.rstms.video.TvLook, mirrored by the wizard's live preview.
 */
public class TvLookSpec {
    private boolean enabled = false;
    /** 0.5-1.3 - multiplies the picture (the camera's exposure of the screen vs the room). */
    private double brightness = 0.92;
    /** 0.6-1.3 - around mid-grey; a filmed screen loses contrast. */
    private double contrast = 0.92;
    /** 0-1.6 - 1 keeps the colours, 0 is black and white. */
    private double saturation = 0.92;
    /** 0-0.25 - the panel's black level: pure black becomes this grey (a lit panel never shows 0). */
    private double blackLevel = 0.05;
    /** -1 (cool, bluish) .. +1 (warm). */
    private double temperature = -0.2;
    /** 0-3 - gaussian blur sigma in delivered-frame px: the camera never resolves the panel perfectly. */
    private double softness = 0.6;
    /** 0-30 - the camera's sensor grain over the screen (ffmpeg noise strength, luma). */
    private double grain = 5;
    /** 0-1 - darker edges and corners (uneven backlight / viewing angle). */
    private double vignette = 0.3;
    /** 0-1 - soft diagonal light reflection on the glass. */
    private double glare = 0.2;
    /** 0-1 - where the reflection band crosses the screen, from its top-left to its bottom-right corner. */
    private double glarePosition = 0.3;
    /** 0-1 - fine horizontal line structure of the panel ("Trame de pixels"). */
    private double scanlines = 0;
    /** The TV's outer outline - the same frame the ambient light starts at (see
     * LightingEffectSpec.frame), same original-unrotated coordinates. The glass effects (vignette,
     * reflection, lines) follow it, including its angle. When null: the lighting frame, then the
     * screen region. */
    private ScreenRegionSpec frame;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public double getBrightness() { return brightness; }
    public void setBrightness(double v) { this.brightness = v; }
    public double getContrast() { return contrast; }
    public void setContrast(double v) { this.contrast = v; }
    public double getSaturation() { return saturation; }
    public void setSaturation(double v) { this.saturation = v; }
    public double getBlackLevel() { return blackLevel; }
    public void setBlackLevel(double v) { this.blackLevel = v; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { this.temperature = v; }
    public double getSoftness() { return softness; }
    public void setSoftness(double v) { this.softness = v; }
    public double getGrain() { return grain; }
    public void setGrain(double v) { this.grain = v; }
    public double getVignette() { return vignette; }
    public void setVignette(double v) { this.vignette = v; }
    public double getGlare() { return glare; }
    public void setGlare(double v) { this.glare = v; }
    public double getGlarePosition() { return glarePosition; }
    public void setGlarePosition(double v) { this.glarePosition = v; }
    public double getScanlines() { return scanlines; }
    public void setScanlines(double v) { this.scanlines = v; }
    public ScreenRegionSpec getFrame() { return frame; }
    public void setFrame(ScreenRegionSpec v) { this.frame = v; }
}

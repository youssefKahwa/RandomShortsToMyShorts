package com.rstms.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Ambient "TV backlight" glow/shadow tuning for one screen ("green"/"blue"/"red"). Unlike
 * ChromaKeySpec, every field here defaults such that a color with no entry (or an explicit
 * enabled=false) is fully inert and adds zero filter-graph nodes at render time - this is opt-in
 * extra encode cost, not an always-on refinement like despill/edgeMargin are.
 */
public class LightingEffectSpec {
    private boolean enabled = false;
    /** The colored glow/light-spill halo around the screen, sourced from that screen's own overlay
     * content each frame - see VideoComposer's insertion point. Only meaningful when enabled=true. */
    private boolean backlightEnabled = true;
    /** A darkening halo composited UNDERNEATH the glow, so the result reads as "the room is
     * naturally dark here" rather than an obviously-added colored rectangle. Only meaningful
     * when enabled=true. */
    private boolean shadowEnabled = true;
    /** 0.0-1.0 friendly "LED brightness" dial - feeds the glow layer's eq brightness/saturation. */
    private double brightness = 0.5;
    /** 0-100 friendly "softness/diffusion" dial (NOT a raw ffmpeg blur sigma) - mapped internally
     * in VideoComposer so the mapping formula can be re-tuned later without a data-model change. */
    private double softness = 0;
    /** 1.1-2.0 - how far the light reaches beyond the TV frame, expressed as how much bigger the
     * whole lit area is than the frame (reach in pixels = (spread-1)/2 * the frame's mean
     * width/height). Floor is 1.1, NOT 1.0: at 1.0 the reach would be zero pixels, i.e. nothing
     * outside the TV to light - a guaranteed-invisible result whatever else is set. */
    private double spread = 1.6;
    /** The TV's outer outline (outer edge of its black border) in the base video - same
     * original-unrotated coordinate convention as JobRequest.screenRegions. The light starts exactly
     * at this frame's edge and is never drawn inside it. Deliberately independent of the screen
     * region the overlay video is placed in: that box is often dragged bigger than the TV (a
     * "zoom" whose excess the chroma key hides), and a light derived from it would start out on the
     * wall. When null, the screen region is used. */
    private ScreenRegionSpec frame;
    /** "No-light" zones: things standing in front of the TV (a cabinet, a soundbar...). Each is a
     * quad in the same coordinate convention as {@link #frame}; inside it neither the glow nor the
     * shadow is drawn (the object hides the wall behind it), with a small soft edge - see
     * LightingMask.occlusion. */
    private List<ScreenRegionSpec> occluders = new ArrayList<>();
    /** Round "no-light" zones (circles/ovals) for objects a box doesn't fit - same rule as
     * {@link #occluders}: no glow and no shadow inside, same soft edge. */
    private List<EllipseZoneSpec> ellipseOccluders = new ArrayList<>();
    /** 0.0-1.0 - alpha strength of the darkening halo. Only meaningful when shadowEnabled=true. */
    private double shadowIntensity = 0.4;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isBacklightEnabled() { return backlightEnabled; }
    public void setBacklightEnabled(boolean v) { this.backlightEnabled = v; }
    public boolean isShadowEnabled() { return shadowEnabled; }
    public void setShadowEnabled(boolean v) { this.shadowEnabled = v; }
    public double getBrightness() { return brightness; }
    public void setBrightness(double v) { this.brightness = v; }
    public double getSoftness() { return softness; }
    public void setSoftness(double v) { this.softness = v; }
    public double getSpread() { return spread; }
    public void setSpread(double v) { this.spread = v; }
    public ScreenRegionSpec getFrame() { return frame; }
    public void setFrame(ScreenRegionSpec v) { this.frame = v; }
    public List<ScreenRegionSpec> getOccluders() { return occluders; }
    public void setOccluders(List<ScreenRegionSpec> v) { this.occluders = v != null ? v : new ArrayList<>(); }
    public List<EllipseZoneSpec> getEllipseOccluders() { return ellipseOccluders; }
    public void setEllipseOccluders(List<EllipseZoneSpec> v) { this.ellipseOccluders = v != null ? v : new ArrayList<>(); }
    public double getShadowIntensity() { return shadowIntensity; }
    public void setShadowIntensity(double v) { this.shadowIntensity = v; }
}

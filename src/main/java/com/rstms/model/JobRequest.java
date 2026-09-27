package com.rstms.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed from the instructions.json uploaded alongside the source video. */
public class JobRequest {
    private String voice;
    private boolean captions = true;
    private List<String> platforms = new ArrayList<>(List.of("youtube", "tiktok", "instagram"));
    private List<SegmentSpec> segments = new ArrayList<>();
    private MusicSpec music;
    private List<SoundEffectSpec> soundEffects = new ArrayList<>();
    private List<BlurRegionSpec> blurRegions = new ArrayList<>();
    private boolean loudnessNormalize = true;
    /** When set and segments is empty, the compose stage transcribes + translates the audio automatically. */
    private AutoScriptSpec autoScript;
    /** "anime" | "football" | null - captured for future category-specific options, no effect yet. */
    private String contentCategory;
    /** e.g. "anime_overlay_text" | "anime_dub_audio" | "green_screen_overlay" | null - which
     * processing style this source video needs; category-specific, still being defined except for
     * green_screen_overlay which is fully implemented. */
    private String videoStyle;
    /** Deprecated - green-only chroma-key tuning, superseded by chromaKey below. Still accepted on
     * parse (and still applied as a fallback for "green" when chromaKey has no entry for it) so an
     * older job's saved instructions.json keeps working, e.g. on resubmit. */
    private GreenScreenSpec greenScreen;
    /** "green"/"blue"/"red" -> chroma-key tuning for that screen. Optional per color - sensible
     * defaults apply for any color with no entry here. */
    private Map<String, ChromaKeySpec> chromaKey = new LinkedHashMap<>();
    /** "green"/"blue"/"red" -> ambient TV-backlight glow/shadow tuning for that screen. Optional
     * per color - absent (or enabled=false) means no effect and zero added filter-graph cost. */
    private Map<String, LightingEffectSpec> lighting = new LinkedHashMap<>();
    /** "green"/"blue"/"red" -> "Rendu TV" filters for that screen (see TvLookSpec). Optional per
     * color - absent (or enabled=false) means no effect and zero added filter-graph cost. */
    private Map<String, TvLookSpec> tvLook = new LinkedHashMap<>();
    /** "green"/"blue"/"red" -> where that screen actually sits in the base video's frame. Required
     * for every color that has an overlay video attached - without it there's no way to know where
     * to fit/place that overlay without stretching it across the whole frame. */
    private Map<String, ScreenRegionSpec> screenRegions = new LinkedHashMap<>();
    /** Manual text/PNG overlay elements ("Incrustations" step) - available for every videoStyle,
     * unrelated to the automatic burned-in dialogue captions. */
    private List<OverlayElementSpec> overlayElements = new ArrayList<>();
    /** Final whole-frame look/quality pass ("Finition & qualité" step) - optional, null/default
     * means no effect. Available for every videoStyle. */
    private FinishingSpec finishing;
    /** 0/90/180/270 - rotates the base video as a whole (e.g. it was recorded sideways or upside
     * down). Applied first, before any region-based effect - screenRegions/blurRegions/
     * overlayElements coordinates are always given relative to the video's ORIGINAL, un-rotated
     * frame (matching what the wizard's preview shows, since it never visually rotates) and are
     * transformed to match internally. Available for every videoStyle. */
    private int sourceRotation = 0;
    /** "green"/"blue"/"red" -> 0/90/180/270, rotating just that overlay clip's own content before
     * it's fitted into its screen region (e.g. the overlay clip itself was filmed sideways) -
     * independent of sourceRotation and of screenRegions, which describe where the (rotated)
     * content goes, not its own orientation. */
    private Map<String, Integer> overlayRotations = new LinkedHashMap<>();

    public String getVoice() { return voice; }
    public void setVoice(String v) { this.voice = v; }
    public boolean isCaptions() { return captions; }
    public void setCaptions(boolean v) { this.captions = v; }
    public List<String> getPlatforms() { return platforms; }
    public void setPlatforms(List<String> v) { this.platforms = v; }
    public List<SegmentSpec> getSegments() { return segments; }
    public void setSegments(List<SegmentSpec> v) { this.segments = v; }
    public MusicSpec getMusic() { return music; }
    public void setMusic(MusicSpec v) { this.music = v; }
    public List<SoundEffectSpec> getSoundEffects() { return soundEffects; }
    public void setSoundEffects(List<SoundEffectSpec> v) { this.soundEffects = v; }
    public List<BlurRegionSpec> getBlurRegions() { return blurRegions; }
    public void setBlurRegions(List<BlurRegionSpec> v) { this.blurRegions = v; }
    public boolean isLoudnessNormalize() { return loudnessNormalize; }
    public void setLoudnessNormalize(boolean v) { this.loudnessNormalize = v; }
    public AutoScriptSpec getAutoScript() { return autoScript; }
    public void setAutoScript(AutoScriptSpec v) { this.autoScript = v; }
    public String getContentCategory() { return contentCategory; }
    public void setContentCategory(String v) { this.contentCategory = v; }
    public String getVideoStyle() { return videoStyle; }
    public void setVideoStyle(String v) { this.videoStyle = v; }
    public GreenScreenSpec getGreenScreen() { return greenScreen; }
    public void setGreenScreen(GreenScreenSpec v) { this.greenScreen = v; }
    public Map<String, ChromaKeySpec> getChromaKey() { return chromaKey; }
    public void setChromaKey(Map<String, ChromaKeySpec> v) { this.chromaKey = v; }
    public Map<String, LightingEffectSpec> getLighting() { return lighting; }
    public void setLighting(Map<String, LightingEffectSpec> v) { this.lighting = v; }
    public Map<String, TvLookSpec> getTvLook() { return tvLook; }
    public void setTvLook(Map<String, TvLookSpec> v) { this.tvLook = v != null ? v : new LinkedHashMap<>(); }
    public Map<String, ScreenRegionSpec> getScreenRegions() { return screenRegions; }
    public void setScreenRegions(Map<String, ScreenRegionSpec> v) { this.screenRegions = v; }
    public List<OverlayElementSpec> getOverlayElements() { return overlayElements; }
    public void setOverlayElements(List<OverlayElementSpec> v) { this.overlayElements = v; }
    public FinishingSpec getFinishing() { return finishing; }
    public void setFinishing(FinishingSpec v) { this.finishing = v; }
    public int getSourceRotation() { return sourceRotation; }
    public void setSourceRotation(int v) { this.sourceRotation = v; }
    public Map<String, Integer> getOverlayRotations() { return overlayRotations; }
    public void setOverlayRotations(Map<String, Integer> v) { this.overlayRotations = v; }
}

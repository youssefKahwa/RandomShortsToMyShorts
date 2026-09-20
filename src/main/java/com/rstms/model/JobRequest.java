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
    /** Chroma-key tuning for "green_screen_overlay" - optional, sensible defaults apply if null. */
    private GreenScreenSpec greenScreen;
    /** "green"/"blue"/"red" -> where that screen actually sits in the base video's frame. Required
     * for every color that has an overlay video attached - without it there's no way to know where
     * to fit/place that overlay without stretching it across the whole frame. */
    private Map<String, ScreenRegionSpec> screenRegions = new LinkedHashMap<>();

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
    public Map<String, ScreenRegionSpec> getScreenRegions() { return screenRegions; }
    public void setScreenRegions(Map<String, ScreenRegionSpec> v) { this.screenRegions = v; }
}

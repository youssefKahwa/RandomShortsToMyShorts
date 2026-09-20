package com.rstms.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Paths paths = new Paths();
    private Tts tts = new Tts();
    private Fit fit = new Fit();
    private Pipeline pipeline = new Pipeline();
    private Video video = new Video();
    private AutoScript autoScript = new AutoScript();
    private Map<String, PlatformConfig> platforms = new LinkedHashMap<>();

    public static class Paths {
        /** Root directory for all job data (input/work/output per job). */
        private String data = "./data";
        /** Root directory for the shared assets library (assets/sfx, assets/music). */
        private String assets = "./assets";
        public String getData() { return data; }
        public void setData(String v) { this.data = v; }
        public String getAssets() { return assets; }
        public void setAssets(String v) { this.assets = v; }
    }

    public static class Tts {
        private String provider = "piper";
        private int maxRetries = 3;
        private long retryBackoffMs = 1500;
        private Piper piper = new Piper();

        public static class Piper {
            private String binary = "piper";
            private String voicesDir = "./voices";
            /** Optional folder of pre-made .mp3 samples (voice id -> filename) for the Voices page to preview. */
            private String samplesDir = "";
            private String defaultVoice = "en_US-lessac-medium";
            private Map<String, String> voices = new LinkedHashMap<>();
            public String getBinary() { return binary; }
            public void setBinary(String v) { this.binary = v; }
            public String getVoicesDir() { return voicesDir; }
            public void setVoicesDir(String v) { this.voicesDir = v; }
            public String getSamplesDir() { return samplesDir; }
            public void setSamplesDir(String v) { this.samplesDir = v; }
            public String getDefaultVoice() { return defaultVoice; }
            public void setDefaultVoice(String v) { this.defaultVoice = v; }
            public Map<String, String> getVoices() { return voices; }
            public void setVoices(Map<String, String> v) { this.voices = v; }
        }

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int v) { this.maxRetries = v; }
        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long v) { this.retryBackoffMs = v; }
        public Piper getPiper() { return piper; }
        public void setPiper(Piper v) { this.piper = v; }
    }

    /** How far narration speaking-rate may be pushed to fit a locked video segment. */
    public static class Fit {
        private double minRate = 0.85;
        private double maxRate = 1.15;
        public double getMinRate() { return minRate; }
        public void setMinRate(double v) { this.minRate = v; }
        public double getMaxRate() { return maxRate; }
        public void setMaxRate(double v) { this.maxRate = v; }
    }

    public static class Pipeline {
        private String ffmpegBinary = "ffmpeg";
        private String ffprobeBinary = "ffprobe";
        private String ffmpegLoglevel = "error";
        /** Bounds how many jobs run at once - ffmpeg is CPU-heavy, tune to your machine. */
        private int maxConcurrentJobs = 2;
        public String getFfmpegBinary() { return ffmpegBinary; }
        public void setFfmpegBinary(String v) { this.ffmpegBinary = v; }
        public String getFfprobeBinary() { return ffprobeBinary; }
        public void setFfprobeBinary(String v) { this.ffprobeBinary = v; }
        public String getFfmpegLoglevel() { return ffmpegLoglevel; }
        public void setFfmpegLoglevel(String v) { this.ffmpegLoglevel = v; }
        public int getMaxConcurrentJobs() { return maxConcurrentJobs; }
        public void setMaxConcurrentJobs(int v) { this.maxConcurrentJobs = v; }
    }

    /** Shared render settings; a platform's own PlatformConfig can override crf/audio bitrate/duration cap. */
    public static class Video {
        private int width = 1080;
        private int height = 1920;
        private int fps = 30;
        private int crf = 20;
        private String preset = "medium";
        private String codec = "libx264";
        private String pixelFormat = "yuv420p";
        private String audioBitrate = "192k";
        public int getWidth() { return width; }
        public void setWidth(int v) { this.width = v; }
        public int getHeight() { return height; }
        public void setHeight(int v) { this.height = v; }
        public int getFps() { return fps; }
        public void setFps(int v) { this.fps = v; }
        public int getCrf() { return crf; }
        public void setCrf(int v) { this.crf = v; }
        public String getPreset() { return preset; }
        public void setPreset(String v) { this.preset = v; }
        public String getCodec() { return codec; }
        public void setCodec(String v) { this.codec = v; }
        public String getPixelFormat() { return pixelFormat; }
        public void setPixelFormat(String v) { this.pixelFormat = v; }
        public String getAudioBitrate() { return audioBitrate; }
        public void setAudioBitrate(String v) { this.audioBitrate = v; }
    }

    /** Local, offline speech-to-text + translation - see scripts/transcribe_translate.py. */
    public static class AutoScript {
        private String pythonBinary = "python";
        private String scriptPath = "./scripts/transcribe_translate.py";
        private String whisperModel = "small";
        private String device = "cpu";
        /** "argos" (default, free/offline), "claude-code" (uses an existing Pro/Max subscription via the local Claude Code CLI), or "claude" (paid API key). Per-job autoScript.translationEngine overrides this. */
        private String translationEngine = "argos";
        private String claudeModel = "claude-sonnet-5";
        private int claudeMaxOutputTokens = 4096;
        /** Path to the Claude Code native binary - version-specific, changes when the VS Code extension updates. */
        private String claudeCodeBinary = "";
        private String claudeCodeModel = "sonnet";
        public String getPythonBinary() { return pythonBinary; }
        public void setPythonBinary(String v) { this.pythonBinary = v; }
        public String getScriptPath() { return scriptPath; }
        public void setScriptPath(String v) { this.scriptPath = v; }
        public String getWhisperModel() { return whisperModel; }
        public void setWhisperModel(String v) { this.whisperModel = v; }
        public String getDevice() { return device; }
        public void setDevice(String v) { this.device = v; }
        public String getTranslationEngine() { return translationEngine; }
        public void setTranslationEngine(String v) { this.translationEngine = v; }
        public String getClaudeModel() { return claudeModel; }
        public void setClaudeModel(String v) { this.claudeModel = v; }
        public int getClaudeMaxOutputTokens() { return claudeMaxOutputTokens; }
        public void setClaudeMaxOutputTokens(int v) { this.claudeMaxOutputTokens = v; }
        public String getClaudeCodeBinary() { return claudeCodeBinary; }
        public void setClaudeCodeBinary(String v) { this.claudeCodeBinary = v; }
        public String getClaudeCodeModel() { return claudeCodeModel; }
        public void setClaudeCodeModel(String v) { this.claudeCodeModel = v; }
    }

    public static class PlatformConfig {
        /** 0 = no trim. */
        private int maxDurationSeconds = 0;
        /** 0 = fall back to Video.crf. */
        private int crf = 0;
        private String audioBitrate;
        public int getMaxDurationSeconds() { return maxDurationSeconds; }
        public void setMaxDurationSeconds(int v) { this.maxDurationSeconds = v; }
        public int getCrf() { return crf; }
        public void setCrf(int v) { this.crf = v; }
        public String getAudioBitrate() { return audioBitrate; }
        public void setAudioBitrate(String v) { this.audioBitrate = v; }
    }

    public Paths getPaths() { return paths; }
    public void setPaths(Paths v) { this.paths = v; }
    public Tts getTts() { return tts; }
    public void setTts(Tts v) { this.tts = v; }
    public Fit getFit() { return fit; }
    public void setFit(Fit v) { this.fit = v; }
    public Pipeline getPipeline() { return pipeline; }
    public void setPipeline(Pipeline v) { this.pipeline = v; }
    public Video getVideo() { return video; }
    public void setVideo(Video v) { this.video = v; }
    public AutoScript getAutoScript() { return autoScript; }
    public void setAutoScript(AutoScript v) { this.autoScript = v; }
    public Map<String, PlatformConfig> getPlatforms() { return platforms; }
    public void setPlatforms(Map<String, PlatformConfig> v) { this.platforms = v; }
}

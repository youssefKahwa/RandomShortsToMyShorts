package com.rstms.tts;

import com.rstms.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Piper - fully local neural TTS. No account, no API key, no credit card, works offline.
 * Ported from the yt-auto project's proven PiperTtsService, trimmed to what this app needs:
 * no SSML/rules engine, no pitch shifting - just text, a voice, and a speaking-rate multiplier
 * used by SegmentFitEngine to make narration fit a locked video segment.
 */
@Service
public class PiperTtsService implements TtsService {

    private static final Logger log = LoggerFactory.getLogger(PiperTtsService.class);
    private static final Pattern SPEAKER_SUFFIX = Pattern.compile("^(.+)#(\\d+)$");

    private final AppProperties props;
    private final Map<String, Integer> sampleRateCache = new HashMap<>();

    public PiperTtsService(AppProperties props) {
        this.props = props;
    }

    @Override
    public Path synthesize(String text, String voice, double speakingRate, Path targetWav) {
        var cfg = props.getTts().getPiper();
        String resolvedVoice = resolveVoice(voice);
        Path model = resolveModel(resolvedVoice);
        Integer speaker = speakerIndex(resolvedVoice);

        try {
            Files.createDirectories(targetWav.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create " + targetWav.getParent(), e);
        }

        List<String> cmd = new ArrayList<>(List.of(
                cfg.getBinary(),
                "-m", model.toAbsolutePath().toString(),
                "-f", targetWav.toAbsolutePath().toString()));

        // Multi-speaker models (e.g. libritts_r) need a --speaker index; single-speaker models reject it.
        if (speaker != null) {
            cmd.addAll(List.of("--speaker", String.valueOf(speaker)));
        }

        // Piper's length-scale is the inverse of speed: 1.25 length = 0.8x speed.
        double lengthScale = 1.0 / Math.max(0.1, speakingRate);
        cmd.addAll(List.of("--length-scale", fmt(lengthScale)));
        cmd.addAll(List.of("--sentence-silence", "0.0"));

        runPiper(cmd, text, model);
        return targetWav;
    }

    private void runPiper(List<String> cmd, String text, Path model) {
        int attempts = Math.max(1, props.getTts().getMaxRetries());
        RuntimeException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                    w.write(text.replace('\n', ' ').trim());
                    w.write('\n');
                }

                StringBuilder out = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (out.length() < 8000) out.append(line).append('\n');
                    }
                }

                if (!p.waitFor(180, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IllegalStateException("Piper timed out after 180s");
                }
                if (p.exitValue() != 0) {
                    throw new IllegalStateException("Piper exited with " + p.exitValue() + ":\n" + out);
                }
                return;

            } catch (Exception e) {
                last = new IllegalStateException(
                        "Piper failed. Command: " + String.join(" ", cmd)
                        + "\n  Model: " + model
                        + "\n  Cause: " + e.getMessage(), e);
                if (attempt < attempts) {
                    try { Thread.sleep(props.getTts().getRetryBackoffMs()); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw last;
    }

    private static String stripSpeakerSuffix(String voiceName) {
        var m = SPEAKER_SUFFIX.matcher(voiceName);
        return m.matches() ? m.group(1) : voiceName;
    }

    private static Integer speakerIndex(String voiceName) {
        var m = SPEAKER_SUFFIX.matcher(voiceName);
        return m.matches() ? Integer.valueOf(m.group(2)) : null;
    }

    private Path resolveModel(String voiceName) {
        var cfg = props.getTts().getPiper();
        String stripped = stripSpeakerSuffix(voiceName);
        Path dir = Path.of(cfg.getVoicesDir());
        Path model = dir.resolve(stripped.endsWith(".onnx") ? stripped : stripped + ".onnx");

        if (!Files.isRegularFile(model)) {
            throw new IllegalStateException(
                    "Piper voice model not found: " + model.toAbsolutePath()
                    + "\n  Download it with:"
                    + "\n    python -m piper.download_voices " + stripped.replace(".onnx", "")
                    + " --data-dir " + dir.toAbsolutePath()
                    + "\n  Or point app.tts.piper.voices-dir at a folder where it already exists."
                    + "\n  Browse voices: https://github.com/OHF-Voice/piper1-gpl/blob/main/VOICES.md");
        }
        Path json = Path.of(model + ".json");
        if (!Files.isRegularFile(json)) {
            throw new IllegalStateException(
                    "Found " + model.getFileName() + " but its config " + json.getFileName()
                    + " is missing. Re-download the voice - both files are required.");
        }
        return model;
    }

    @Override
    public int sampleRateHertz(String voice) {
        String resolvedVoice = resolveVoice(voice);
        return sampleRateCache.computeIfAbsent(resolvedVoice, v -> {
            try {
                Path model = resolveModel(v);
                String json = Files.readString(Path.of(model + ".json"), StandardCharsets.UTF_8);
                var m = Pattern.compile("\"sample_rate\"\\s*:\\s*(\\d+)").matcher(json);
                return m.find() ? Integer.parseInt(m.group(1)) : 22050;
            } catch (Exception e) {
                log.debug("Could not read sample rate for voice {}, assuming 22050 Hz", v);
                return 22050;
            }
        });
    }

    @Override
    public String resolveVoice(String alias) {
        var cfg = props.getTts().getPiper();
        String key = (alias == null || alias.isBlank()) ? cfg.getDefaultVoice() : alias.trim();
        String mapped = cfg.getVoices().get(key);
        if (mapped != null) return mapped;
        // Allow a raw model name straight from the instructions, e.g. en_US-lessac-medium
        if (key.matches("[a-z]{2,3}_[A-Z]{2}-.+")) return key;
        throw new IllegalArgumentException("Unknown voice '" + key + "'. Define it under "
                + "app.tts.piper.voices in application.yml, or use a model name like "
                + "en_US-lessac-medium. Known aliases: " + cfg.getVoices().keySet());
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.4f", d); }

    @Override
    public String describe() { return "Piper (local, offline)"; }
}

package com.rstms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rstms.config.AppProperties;
import com.rstms.model.SegmentSpec;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Automatic speech-to-text + translation: transcribes the source video's original-language audio
 * with real timestamps (faster-whisper, always local/free) and translates each line into English,
 * using either Argos Translate (local/free, default) or Claude (paid API, opt-in, better quality -
 * see ClaudeTranslationService). Produces the same List<SegmentSpec> a hand-written or timed-script
 * narration would - the rest of the pipeline doesn't know or care where segments came from.
 */
@Component
public class AutoScriptService {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final ClaudeTranslationService claudeTranslation;
    private final ClaudeCodeTranslationService claudeCodeTranslation;

    public AutoScriptService(AppProperties props, ObjectMapper mapper, ClaudeTranslationService claudeTranslation,
                              ClaudeCodeTranslationService claudeCodeTranslation) {
        this.props = props;
        this.mapper = mapper;
        this.claudeTranslation = claudeTranslation;
        this.claudeCodeTranslation = claudeCodeTranslation;
    }

    public List<SegmentSpec> transcribeAndTranslate(Path sourceVideo, String sourceLanguage, String translationEngine, String context) {
        var cfg = props.getAutoScript();
        String engine = translationEngine != null ? translationEngine : cfg.getTranslationEngine();
        boolean skipLocalTranslation = "claude".equalsIgnoreCase(engine) || "claude-code".equalsIgnoreCase(engine);

        List<SegmentSpec> transcribed = runWhisper(sourceVideo, sourceLanguage, skipLocalTranslation ? "none" : "argos");
        if ("claude".equalsIgnoreCase(engine)) {
            return claudeTranslation.translate(transcribed, sourceLanguage, context);
        }
        if ("claude-code".equalsIgnoreCase(engine)) {
            return claudeCodeTranslation.translate(transcribed, sourceLanguage, context);
        }
        return transcribed;
    }

    private List<SegmentSpec> runWhisper(Path sourceVideo, String sourceLanguage, String pythonTranslationEngine) {
        var cfg = props.getAutoScript();
        List<String> cmd = List.of(
                cfg.getPythonBinary(),
                cfg.getScriptPath(),
                "--input", sourceVideo.toAbsolutePath().toString(),
                "--source-lang", sourceLanguage,
                "--model", cfg.getWhisperModel(),
                "--device", cfg.getDevice(),
                "--translation-engine", pythonTranslationEngine
        );

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Belt-and-suspenders alongside the script's own sys.stdout.reconfigure(): Windows'
            // console codepage can't represent Arabic/accented text, and Java reads this process's
            // stdout as UTF-8 - a mismatch here would silently corrupt anything beyond plain ASCII.
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread errThread = new Thread(() -> readInto(p.getErrorStream(), err));
            errThread.start();
            readInto(p.getInputStream(), out);
            errThread.join(5000);

            if (!p.waitFor(900, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("La transcription/traduction automatique a dépassé 900s");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException(
                        "Échec de la transcription/traduction automatique (code " + p.exitValue() + ") : "
                        + err + "\nCommande : " + String.join(" ", cmd));
            }

            SegmentSpec[] segments = mapper.readValue(out.toString(), SegmentSpec[].class);
            if (segments.length == 0) {
                throw new IllegalStateException(
                        "Aucune parole détectée dans la vidéo, ou la langue source ('" + sourceLanguage
                        + "') ne correspond pas à ce qui est réellement parlé.");
            }
            return new ArrayList<>(List.of(segments));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Impossible de lancer la transcription automatique. Avez-vous exécuté "
                    + "scripts/setup-auto-script.ps1 ? Cause : " + e.getMessage(), e);
        }
    }

    private static void readInto(java.io.InputStream stream, StringBuilder sb) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) { }
    }
}

package com.rstms.service;

import com.rstms.config.AppProperties;
import com.rstms.model.BlurRegionSpec;
import com.rstms.model.JobRequest;
import com.rstms.model.SegmentSpec;
import com.rstms.model.SoundEffectSpec;
import com.rstms.model.VoiceInfo;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Checks a JobRequest against a video's real duration, collecting every problem found rather than
 * failing on the first one - used both as a pre-flight (the "Validate" button, no job created) and
 * as the gate before a real compose run starts, so a typo doesn't burn a minute of TTS/ffmpeg work
 * before surfacing.
 */
@Component
public class JobValidator {

    public record Result(List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    private final AssetStore assets;
    private final VoiceCatalogService voiceCatalog;
    private final AppProperties props;

    public JobValidator(AssetStore assets, VoiceCatalogService voiceCatalog, AppProperties props) {
        this.assets = assets;
        this.voiceCatalog = voiceCatalog;
        this.props = props;
    }

    public Result check(JobRequest request, double videoDuration) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (request.getPlatforms() == null || request.getPlatforms().isEmpty()) {
            errors.add("Au moins une plateforme doit être choisie");
        }

        List<SegmentSpec> sorted = new ArrayList<>(request.getSegments());
        sorted.sort((a, b) -> Double.compare(a.getStart(), b.getStart()));
        double lastEnd = 0;
        for (SegmentSpec seg : sorted) {
            String where = String.format(Locale.ROOT, "Le segment %.1fs-%.1fs", seg.getStart(), seg.getEnd());
            if (seg.getEnd() <= seg.getStart()) {
                errors.add(where + " se termine avant (ou en même temps) qu'il ne commence");
            }
            if (seg.getText() == null || seg.getText().isBlank()) {
                errors.add(where + " n'a pas de texte");
            }
            if (seg.getStart() < lastEnd - 0.001) {
                errors.add(where + " chevauche le segment précédent");
            }
            if (seg.getEnd() > videoDuration + 0.5) {
                errors.add(where + " se termine après la fin réelle de la vidéo ("
                        + String.format(Locale.ROOT, "%.1fs", videoDuration) + ")");
            }
            lastEnd = seg.getEnd();
        }

        for (SoundEffectSpec fx : request.getSoundEffects()) {
            if (fx.getStart() < 0 || fx.getStart() > videoDuration) {
                errors.add("L'effet sonore à " + fx.getStart() + "s tombe en dehors de la vidéo");
            }
            if (fx.getFile() == null || fx.getFile().isBlank()) {
                errors.add("Un effet sonore n'a pas de fichier ('file')");
            } else {
                try {
                    assets.resolve(AssetStore.Kind.SFX, fx.getFile());
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        for (BlurRegionSpec r : request.getBlurRegions()) {
            if (r.getEnd() <= r.getStart()) {
                errors.add("La zone de flou " + r.getStart() + "-" + r.getEnd() + " se termine avant (ou en même temps) qu'elle ne commence");
            }
        }

        if (request.getMusic() != null) {
            String file = request.getMusic().getFile();
            if (file == null || file.isBlank()) {
                errors.add("Une musique est activée mais sans fichier ('file')");
            } else {
                try {
                    assets.resolve(AssetStore.Kind.MUSIC, file);
                } catch (IllegalArgumentException e) {
                    errors.add(e.getMessage());
                }
            }
        }

        errors.addAll(checkAutoScriptPreconditions(request).errors());

        checkVoices(request, errors, warnings);

        return new Result(errors, warnings);
    }

    /**
     * Cheap checks (missing language, missing API key) that must run before the expensive
     * transcription step, not just at pre-flight time - otherwise a real job run burns a minute of
     * Whisper transcription before discovering it can't call Claude, defeating the whole point of
     * failing fast. check() above also calls this, so pre-flight /validate catches it too.
     */
    public Result checkAutoScriptPreconditions(JobRequest request) {
        List<String> errors = new ArrayList<>();
        if (request.getAutoScript() != null) {
            String lang = request.getAutoScript().getSourceLanguage();
            if (lang == null || lang.isBlank()) {
                errors.add("La transcription automatique est activée mais aucune langue source n'est indiquée");
            }
            String engine = request.getAutoScript().getTranslationEngine();
            String effectiveEngine = engine != null ? engine : props.getAutoScript().getTranslationEngine();
            if ("claude".equalsIgnoreCase(effectiveEngine)
                    && (System.getenv("ANTHROPIC_API_KEY") == null || System.getenv("ANTHROPIC_API_KEY").isBlank())) {
                errors.add("Le moteur de traduction Claude est sélectionné mais ANTHROPIC_API_KEY n'est pas "
                        + "défini - créez une clé sur console.anthropic.com et définissez-la comme variable "
                        + "d'environnement (distinct d'un abonnement claude.ai).");
            }
            if ("claude-code".equalsIgnoreCase(effectiveEngine)) {
                String binary = props.getAutoScript().getClaudeCodeBinary();
                if (binary == null || binary.isBlank() || !Files.isRegularFile(Path.of(binary))) {
                    errors.add("Le moteur Claude Code est sélectionné mais app.auto-script.claude-code-binary "
                            + "n'est pas configuré ou introuvable ('" + binary + "').");
                }
            }
        }
        return new Result(errors, List.of());
    }

    /** Flags a voice the catalog knows isn't commercial-safe, and errors if a needed voice isn't downloaded at all. */
    private void checkVoices(JobRequest request, List<String> errors, List<String> warnings) {
        // Segments are empty right now if autoScript will populate them at compose time - TTS still applies.
        boolean needsTts = !request.getSegments().isEmpty() || request.getAutoScript() != null;
        Set<String> voicesUsed = new LinkedHashSet<>();
        voicesUsed.add(request.getVoice() != null ? request.getVoice() : props.getTts().getPiper().getDefaultVoice());
        for (SegmentSpec seg : request.getSegments()) {
            if (seg.getVoice() != null) voicesUsed.add(seg.getVoice());
        }

        for (String voiceId : voicesUsed) {
            var known = voiceCatalog.find(voiceId);
            if (known.isPresent()) {
                VoiceInfo v = known.get();
                if (!v.isCommercialSafe()) {
                    warnings.add("La voix '" + voiceId + "' est " + v.getLicense()
                            + " - ne l'utilisez pas sur une chaîne monétisée sans droits séparés.");
                }
                if (needsTts && !voiceCatalog.isDownloaded(v)) {
                    errors.add("La voix '" + voiceId + "' n'est pas encore téléchargée - voir /voices pour la commande exacte.");
                }
            } else if (needsTts) {
                Path model = Path.of(props.getTts().getPiper().getVoicesDir(), voiceId + ".onnx");
                if (!Files.isRegularFile(model)) {
                    errors.add("La voix '" + voiceId + "' est introuvable dans le dossier de voix configuré.");
                }
            }
        }
    }
}

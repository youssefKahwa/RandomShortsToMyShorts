package com.rstms.service;

import com.rstms.config.AppProperties;
import com.rstms.model.BlurRegionSpec;
import com.rstms.model.ChromaKeySpec;
import com.rstms.model.EllipseZoneSpec;
import com.rstms.model.FinishingSpec;
import com.rstms.model.JobRequest;
import com.rstms.model.LightingEffectSpec;
import com.rstms.model.OverlayElementSpec;
import com.rstms.model.ScreenRegionSpec;
import com.rstms.model.SegmentSpec;
import com.rstms.model.SoundEffectSpec;
import com.rstms.model.TvLookSpec;
import com.rstms.model.VoiceInfo;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Kept in sync with the preset branches in VideoComposer.buildFinishingFilter - "none" is
     * handled separately above since it's the absence of a look, not one of these. */
    private static final Set<String> LOOK_PRESETS =
            Set.of("match_grain", "cinematic", "handheld_vignette", "premium");
    private static final Set<Integer> ROTATIONS = Set.of(0, 90, 180, 270);

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
            if (r.getEnd() == null) {
                errors.add("La zone de flou à " + r.getStart() + "s n'a pas d'heure de fin ('end' est manquant ou nul dans les instructions)");
            } else if (r.getEnd() <= r.getStart()) {
                errors.add("La zone de flou " + r.getStart() + "-" + r.getEnd() + " se termine avant (ou en même temps) qu'elle ne commence");
            }
            // ffmpeg's boxblur luma radius must stay under half the SMALLER dimension of the region
            // being blurred (verified empirically: a 573x305 region capped at 152, a 283x251 region
            // capped at 125 - both exactly match min(width,height)/2, integer division). There is no
            // fixed ceiling across all zones - a small zone (a logo, a face) has a much lower valid
            // max than a large one, so this must be computed per-region, not against one constant.
            int maxStrength = Math.min(r.getWidth(), r.getHeight()) / 2 - 1;
            if (r.getStrength() < 1 || r.getStrength() > maxStrength) {
                errors.add("L'intensité du flou (" + r.getStrength() + ") à " + r.getStart()
                        + "s doit être comprise entre 1 et " + maxStrength + " pour une zone de "
                        + r.getWidth() + "x" + r.getHeight() + " pixels (la limite dépend de la "
                        + "taille de la zone - réduisez l'intensité ou agrandissez la zone)");
            }
        }

        for (ChromaKeySpec tuning : request.getChromaKey().values()) {
            if (tuning.getColor() != null && !tuning.getColor().matches("^#?[0-9a-fA-F]{6}$")
                    && !tuning.getColor().matches("^0x[0-9a-fA-F]{6}$")) {
                errors.add("La couleur de détourage '" + tuning.getColor() + "' n'est pas une couleur hexadécimale valide");
            }
            // Ceiling lowered from the old 0.6: confirmed against real footage that a near-max
            // similarity combined with a generous edgeMargin doesn't just loosen the match on the
            // screen's own pixels, it starts matching background wall/room colors too, which
            // breaks edgeMargin's own "self-correcting" guarantee (ChromaKeySpec.edgeMargin's doc)
            // and lets overlay content bleed onto the wall around the screen, not just the screen
            // itself - reproduced and confirmed via a real user job (edgeMargin 23.5%, similarity
            // 0.6) where the composited overlay visibly covered ~94% of the frame width instead of
            // the traced ~77%-wide screen.
            if (tuning.getSimilarity() < 0.02 || tuning.getSimilarity() > 0.4) {
                errors.add("La similarité du détourage doit être comprise entre 0.02 et 0.4 "
                        + "(au-delà, le détourage peut aussi retirer des pixels du mur ou de la pièce "
                        + "autour de l'écran, pas seulement l'écran lui-même)");
            }
            if (tuning.getBlend() < 0 || tuning.getBlend() > 0.5) {
                errors.add("Le fondu du détourage doit être compris entre 0 et 0.5");
            }
            if (tuning.getDespill() < 0 || tuning.getDespill() > 1) {
                errors.add("L'anti-débordement du détourage doit être compris entre 0 et 1");
            }
            // Ceiling lowered from the old 25% to match ChromaKeySpec.edgeMargin's own documented
            // intended range (0-15%): the same real job above had 23.5%, growing the keyed/pasted
            // region to ~94% of the frame's width.
            if (tuning.getEdgeMargin() < 0 || tuning.getEdgeMargin() > 15) {
                errors.add("La marge de sécurité des bords doit être comprise entre 0 et 15% "
                        + "(au-delà, la zone composée peut déborder largement sur le mur autour de l'écran)");
            }
        }

        for (LightingEffectSpec light : request.getLighting().values()) {
            if (light.getBrightness() < 0 || light.getBrightness() > 1) {
                errors.add("La luminosité de l'éclairage ambiant doit être comprise entre 0 et 1");
            }
            if (light.getSoftness() < 0 || light.getSoftness() > 100) {
                errors.add("La douceur de l'éclairage ambiant doit être comprise entre 0 et 100");
            }
            // Floor is 1.1, not 1.0 - see LightingEffectSpec.spread's own doc: at exactly 1.0 the
            // light's reach is zero pixels, so there is nothing outside the TV to light.
            if (light.getSpread() < 1.1 || light.getSpread() > 2.0) {
                errors.add("L'étendue de l'éclairage ambiant doit être comprise entre 1.1 et 2.0 "
                        + "(à 1.0, la lueur n'aurait aucune portée au-delà de l'écran et serait invisible)");
            }
            if (light.getShadowIntensity() < 0 || light.getShadowIntensity() > 1) {
                errors.add("L'intensité de l'ombre portée de l'éclairage ambiant doit être comprise entre 0 et 1");
            }
            if (light.getFrame() != null && !isValidFrame(light.getFrame())) {
                // Bounded, not just non-null: the frame sizes the light's mask image (w*h bytes), so
                // an absurd pasted coordinate must not become a huge allocation.
                errors.add("Le cadre du téléviseur de l'éclairage ambiant doit avoir ses 4 coins, "
                        + "avec des coordonnées comprises entre -20000 et 20000 px");
            }
            // Each zone is evaluated for every pixel of the lit area, so the count is capped too.
            if (light.getOccluders().size() + light.getEllipseOccluders().size() > 20) {
                errors.add("L'éclairage ambiant accepte au plus 20 zones sans lumière par écran");
            }
            for (EllipseZoneSpec e : light.getEllipseOccluders()) {
                if (e == null || !Double.isFinite(e.getCx()) || !Double.isFinite(e.getCy())
                        || !Double.isFinite(e.getRx()) || !Double.isFinite(e.getRy())
                        || Math.abs(e.getCx()) > 20000 || Math.abs(e.getCy()) > 20000
                        || e.getRx() <= 0 || e.getRy() <= 0 || e.getRx() > 20000 || e.getRy() > 20000) {
                    errors.add("Chaque zone ronde sans lumière doit avoir un centre et deux rayons valides "
                            + "(rayons > 0, valeurs entre -20000 et 20000 px)");
                    break;
                }
            }
            for (ScreenRegionSpec zone : light.getOccluders()) {
                if (zone == null || !isValidFrame(zone)) {
                    errors.add("Chaque zone sans lumière doit avoir ses 4 coins, avec des coordonnées comprises "
                            + "entre -20000 et 20000 px");
                    break;
                }
            }
        }

        for (TvLookSpec tv : request.getTvLook().values()) {
            if (tv == null) continue;
            checkRange(errors, tv.getBrightness(), 0.5, 1.3, "Luminosité");
            checkRange(errors, tv.getContrast(), 0.6, 1.3, "Contraste");
            checkRange(errors, tv.getSaturation(), 0, 1.6, "Saturation");
            checkRange(errors, tv.getBlackLevel(), 0, 0.25, "Noir de l'écran");
            checkRange(errors, tv.getTemperature(), -1, 1, "Température");
            checkRange(errors, tv.getSoftness(), 0, 3, "Douceur");
            checkRange(errors, tv.getGrain(), 0, 30, "Grain");
            checkRange(errors, tv.getVignette(), 0, 1, "Bords assombris");
            checkRange(errors, tv.getGlare(), 0, 1, "Reflet");
            checkRange(errors, tv.getGlarePosition(), 0, 1, "Position du reflet");
            checkRange(errors, tv.getScanlines(), 0, 1, "Trame de pixels");
            if (tv.getFrame() != null && !isValidFrame(tv.getFrame())) {
                // Bounded for the same reason as the lighting frame: it sizes two mask images.
                errors.add("Le cadre du téléviseur du rendu TV doit avoir ses 4 coins, "
                        + "avec des coordonnées comprises entre -20000 et 20000 px");
            }
        }

        for (OverlayElementSpec el : request.getOverlayElements()) {
            String where = "Une incrustation";
            if (el.getEnd() > 0 && el.getEnd() <= el.getStart()) {
                errors.add(where + " (" + el.getStart() + "-" + el.getEnd() + "s) se termine avant (ou en même temps) qu'elle ne commence");
            }
            if ("text".equals(el.getType())) {
                if (el.getText() == null || el.getText().isBlank()) {
                    errors.add(where + " de texte n'a pas de contenu");
                }
            } else if ("image".equals(el.getType())) {
                if (el.getId() == null || el.getId().isBlank()) {
                    errors.add(where + " image n'a pas d'identifiant - impossible de la relier au fichier envoyé");
                }
            } else {
                errors.add(where + " a un type inconnu ('" + el.getType() + "') - attendu 'text' ou 'image'");
            }
            if (el.getWidth() <= 0 || el.getHeight() <= 0) {
                errors.add(where + " a une taille invalide (largeur/hauteur doivent être positives)");
            }
            if (el.getColor() != null && !el.getColor().isBlank() && !el.getColor().matches("^#?[0-9a-fA-F]{6}$")) {
                errors.add(where + " a une couleur invalide ('" + el.getColor() + "')");
            }
        }

        FinishingSpec finishing = request.getFinishing();
        if (finishing != null) {
            String preset = finishing.getLookPreset();
            if (preset != null && !preset.isBlank() && !preset.equals("none") && !LOOK_PRESETS.contains(preset)) {
                errors.add("Le style de finition '" + preset + "' est inconnu");
            }
            if (finishing.getEnhanceIntensity() < 0 || finishing.getEnhanceIntensity() > 1) {
                errors.add("L'intensité de l'amélioration doit être comprise entre 0 et 1");
            }
        }

        if (!ROTATIONS.contains(request.getSourceRotation())) {
            errors.add("La rotation de la vidéo (" + request.getSourceRotation() + "°) doit être 0, 90, 180 ou 270");
        }
        for (Map.Entry<String, Integer> e : request.getOverlayRotations().entrySet()) {
            if (!ROTATIONS.contains(e.getValue())) {
                errors.add("La rotation de l'écran " + e.getKey() + " (" + e.getValue() + "°) doit être 0, 90, 180 ou 270");
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

    private static boolean isValidFrame(ScreenRegionSpec f) {
        ScreenRegionSpec.Corner[] corners = {f.getTopLeft(), f.getTopRight(), f.getBottomRight(), f.getBottomLeft()};
        for (ScreenRegionSpec.Corner c : corners) {
            if (c == null) return false;
            if (!Double.isFinite(c.getX()) || !Double.isFinite(c.getY())) return false;
            if (Math.abs(c.getX()) > 20000 || Math.abs(c.getY()) > 20000) return false;
        }
        return true;
    }

    /** A "Rendu TV" setting outside its slider range. Rejects NaN too - a plain "v < lo || v > hi"
     * lets it through. */
    private static void checkRange(List<String> errors, double v, double lo, double hi, String setting) {
        if (!(v >= lo && v <= hi)) {
            errors.add("Rendu TV - " + setting + " : valeur attendue entre " + fmt(lo) + " et " + fmt(hi));
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}

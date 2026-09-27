package com.rstms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rstms.model.ChromaKeySpec;
import com.rstms.model.GreenScreenSpec;
import com.rstms.model.Job;
import com.rstms.model.JobRequest;
import com.rstms.model.JobStatus;
import com.rstms.model.LightingEffectSpec;
import com.rstms.model.OverlayElementSpec;
import com.rstms.model.ScreenRegionSpec;
import com.rstms.model.SegmentSpec;
import com.rstms.model.SoundEffectSpec;
import com.rstms.video.MediaProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Runs the localization pipeline for one job in two stages:
 *   compose() - narration fit, ducking, effects, music, blur, captions -> one master.mp4, then
 *               the job stops at REVIEW so it can be watched in the browser before spending time
 *               on per-platform renders.
 *   export()  - triggered explicitly (the "Render platform exports" button) - renders the final
 *               per-platform files from that reviewed master.
 */
@Component
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobStore store;
    private final ObjectMapper mapper;
    private final MediaProbe probe;
    private final SegmentFitEngine fitEngine;
    private final AudioMuxer audioMuxer;
    private final CaptionWriter captionWriter;
    private final VideoComposer videoComposer;
    private final PlatformExporter platformExporter;
    private final AssetStore assets;
    private final JobValidator validator;
    private final AutoScriptService autoScript;
    private final Executor renderExecutor;

    public JobProcessor(JobStore store, ObjectMapper mapper, MediaProbe probe,
                         SegmentFitEngine fitEngine, AudioMuxer audioMuxer, CaptionWriter captionWriter,
                         VideoComposer videoComposer, PlatformExporter platformExporter, AssetStore assets,
                         JobValidator validator, AutoScriptService autoScript,
                         @Qualifier("renderExecutor") Executor renderExecutor) {
        this.store = store;
        this.mapper = mapper;
        this.probe = probe;
        this.fitEngine = fitEngine;
        this.audioMuxer = audioMuxer;
        this.captionWriter = captionWriter;
        this.videoComposer = videoComposer;
        this.platformExporter = platformExporter;
        this.assets = assets;
        this.validator = validator;
        this.autoScript = autoScript;
        this.renderExecutor = renderExecutor;
    }

    public void compose(String jobId) {
        Job job = store.find(jobId).orElseThrow(() -> new IllegalStateException("Unknown job " + jobId));
        try {
            runComposeStage(job);
        } catch (Exception e) {
            fail(job, e);
        }
    }

    public void export(String jobId) {
        Job job = store.find(jobId).orElseThrow(() -> new IllegalStateException("Unknown job " + jobId));
        try {
            runExportStage(job);
        } catch (Exception e) {
            fail(job, e);
        }
    }

    private void fail(Job job, Exception e) {
        log.error("Job {} failed", job.getId(), e);
        job.setStatus(JobStatus.FAILED);
        job.setError(e.getMessage() == null ? e.toString() : e.getMessage());
        job.setUpdatedAt(Instant.now());
        store.save(job);
    }

    private void runComposeStage(Job job) throws Exception {
        job.setStatus(JobStatus.PROCESSING);
        touch(job, "lecture des instructions");

        Path input = store.inputDir(job.getId());
        Path work = store.workDir(job.getId());
        Files.createDirectories(work);

        Path sourceVideo = input.resolve(job.getSourceVideoName());
        JobRequest request = mapper.readValue(input.resolve("instructions.json").toFile(), JobRequest.class);

        Map<String, Path> overlayVideos = new LinkedHashMap<>();
        if ("green_screen_overlay".equals(request.getVideoStyle())) {
            for (Map.Entry<String, String> e : job.getOverlayVideoNames().entrySet()) {
                Path p = input.resolve(e.getValue());
                if (!Files.isRegularFile(p)) {
                    throw new IllegalArgumentException(
                            "La vidéo d'incrustation (écran " + e.getKey() + ") est introuvable : " + p);
                }
                overlayVideos.put(e.getKey(), p);
            }
            if (!overlayVideos.containsKey("green")) {
                throw new IllegalArgumentException("Le style 'incrustation fond vert' est sélectionné mais aucune "
                        + "vidéo pour l'écran vert n'a été envoyée.");
            }
            for (String color : overlayVideos.keySet()) {
                if (!request.getScreenRegions().containsKey(color)) {
                    throw new IllegalArgumentException("Aucune position (x/y/largeur/hauteur) n'a été indiquée "
                            + "pour l'écran " + color + " - sans elle, impossible de savoir où placer sa vidéo.");
                }
            }
        }
        Path greenOverlayVideo = overlayVideos.get("green");

        // "Incrustations" overlay elements apply regardless of videoStyle - resolve each image
        // element's uploaded PNG the same way the screen overlay videos are resolved above.
        Map<String, Path> overlayImages = new LinkedHashMap<>();
        for (OverlayElementSpec el : request.getOverlayElements()) {
            if (!"image".equals(el.getType())) continue;
            String filename = job.getOverlayImageNames().get(el.getId());
            if (filename == null) {
                throw new IllegalArgumentException(
                        "Une incrustation image ('" + el.getId() + "') n'a pas de fichier envoyé.");
            }
            Path p = input.resolve(filename);
            if (!Files.isRegularFile(p)) {
                throw new IllegalArgumentException(
                        "L'image d'incrustation ('" + el.getId() + "') est introuvable : " + p);
            }
            overlayImages.put(el.getId(), p);
        }

        if (request.getAutoScript() != null && request.getSegments().isEmpty()) {
            // Fails fast on a missing language/API key before spending minutes on transcription -
            // the whole point of validating anything up front.
            JobValidator.Result preCheck = validator.checkAutoScriptPreconditions(request);
            if (!preCheck.ok()) {
                throw new IllegalArgumentException(String.join("; ", preCheck.errors()));
            }
            touch(job, "transcription et traduction automatiques");
            var autoSpec = request.getAutoScript();
            request.setSegments(autoScript.transcribeAndTranslate(
                    sourceVideo, autoSpec.getSourceLanguage(), autoSpec.getTranslationEngine(), autoSpec.getContext()));
        }

        // A green-screen base video is a reusable room/template shot that's expected to loop
        // seamlessly and is normally shorter than the actual content being shown on it - so the
        // green overlay's own length drives the result instead of the base's, unlike every other
        // job type where the (only) source video's duration is naturally authoritative. Any
        // blue/red overlay loops/trims to match this same duration, same as the base video.
        double videoDuration = greenOverlayVideo != null
                ? probe.durationSeconds(greenOverlayVideo)
                : probe.durationSeconds(sourceVideo);
        clampSegmentsToVideoDuration(job, request, videoDuration);
        JobValidator.Result validation = validator.check(request, videoDuration);
        job.getWarnings().addAll(validation.warnings());
        if (!validation.ok()) {
            throw new IllegalArgumentException(String.join("; ", validation.errors()));
        }

        touch(job, "synthèse de la narration");
        List<AudioMuxer.PlacedSegment> placed = new ArrayList<>();
        int idx = 0;
        for (SegmentSpec seg : request.getSegments()) {
            var fit = fitEngine.fit(seg, request.getVoice(), work, idx++);
            if (fit.warning() != null) {
                job.getWarnings().add(fit.warning());
            }
            placed.add(new AudioMuxer.PlacedSegment(seg, fit.wav()));
        }

        List<AudioMuxer.PlacedEffect> effects = new ArrayList<>();
        for (SoundEffectSpec fx : request.getSoundEffects()) {
            Path file = assets.resolve(AssetStore.Kind.SFX, fx.getFile());
            effects.add(new AudioMuxer.PlacedEffect(fx.getStart(), file, fx.getVolume()));
        }

        List<AudioMuxer.MusicBed> beds = new ArrayList<>();
        if (request.getMusic() != null) {
            Path file = assets.resolve(AssetStore.Kind.MUSIC, request.getMusic().getFile());
            if (probe.hasAudioStream(file)) {
                beds.add(new AudioMuxer.MusicBed(file, request.getMusic().getVolume()));
            } else {
                job.getWarnings().add("La musique '" + request.getMusic().getFile() + "' n'a pas de piste audio - ignorée.");
            }
        }
        for (Map.Entry<String, Path> e : overlayVideos.entrySet()) {
            // Each overlay clip's own audio (dialogue, commentary, sound effects) plays alongside
            // the base video's audio - otherwise it'd just be a silent picture on the "TV". A few
            // overlay clips have no audio track at all though, so this is checked rather than
            // assumed - same lesson as the base video's own possibly-missing audio track below.
            if (probe.hasAudioStream(e.getValue())) {
                beds.add(new AudioMuxer.MusicBed(e.getValue(), 1.0));
            } else {
                job.getWarnings().add("La vidéo d'incrustation (écran " + e.getKey() + ") n'a pas de piste audio.");
            }
        }

        touch(job, "mixage audio");
        boolean sourceHasAudio = probe.hasAudioStream(sourceVideo);
        Path mixedAudio = audioMuxer.buildMasterAudio(sourceVideo, sourceHasAudio, videoDuration, placed, effects,
                beds, request.isLoudnessNormalize(), work.resolve("mixed_audio.wav"));

        Path captionsSrt = null;
        if (request.isCaptions() && !request.getSegments().isEmpty()) {
            captionsSrt = captionWriter.write(request.getSegments(), work.resolve("captions.srt"));
        }

        Map<String, ChromaKeySpec> chromaKey = new LinkedHashMap<>(request.getChromaKey());
        // Legacy fallback: an older job's instructions.json may still carry the green-only
        // GreenScreenSpec instead of the new per-color chromaKey map - honor it for "green" when
        // the new map has no entry of its own, so a resubmit of an old job keeps behaving the same.
        if (!chromaKey.containsKey("green") && request.getGreenScreen() != null) {
            GreenScreenSpec legacy = request.getGreenScreen();
            ChromaKeySpec converted = new ChromaKeySpec();
            converted.setColor(legacy.getColor());
            converted.setSimilarity(legacy.getSimilarity());
            converted.setBlend(legacy.getBlend());
            chromaKey.put("green", converted);
        }

        touch(job, "composition de la vidéo");
        Path masterVideo = work.resolve("master.mp4");
        int[] frameSize = probe.videoSize(sourceVideo);
        Map<String, LightingEffectSpec> lighting = request.getLighting();
        videoComposer.compose(sourceVideo, mixedAudio, captionsSrt, request.getBlurRegions(),
                overlayVideos, request.getScreenRegions(), chromaKey, lighting, request.getTvLook(), request.getOverlayElements(),
                overlayImages, request.getFinishing(), frameSize[0], frameSize[1],
                request.getSourceRotation(), request.getOverlayRotations(), videoDuration, masterVideo);

        if (!overlayVideos.isEmpty()) {
            // Catches the single most damning "this is a green-screen composite" tell - a sliver of
            // raw, un-keyed screen color still visible at a screen's edge in the actual delivered
            // video - so it's surfaced as an actionable warning instead of only being found by
            // someone watching closely (or by the audience).
            touch(job, "vérification des bords d'écran");
            // verifyScreenEdges reads the REAL (already-rotated) composed video, so it needs the
            // same rotated coordinates compose() just used internally, not the raw traced ones.
            Map<String, ScreenRegionSpec> regionsForVerification = videoComposer.rotateScreenRegions(
                    request.getScreenRegions(), request.getSourceRotation(), frameSize[0], frameSize[1]);
            // Where each lit screen's ambient light may be drawn: everything outside its TV frame. The
            // check must ignore those pixels - a TV showing a football pitch lights the wall green,
            // which is the right look but reads as "key color" to it.
            Map<String, ScreenRegionSpec> litFrames = new LinkedHashMap<>();
            for (String color : overlayVideos.keySet()) {
                LightingEffectSpec light = lighting != null ? lighting.get(color) : null;
                if (light == null || !light.isEnabled() || !(light.isBacklightEnabled() || light.isShadowEnabled())) continue;
                ScreenRegionSpec litFrame = light.getFrame() != null ? light.getFrame() : request.getScreenRegions().get(color);
                if (litFrame != null) litFrames.put(color, litFrame);
            }
            litFrames = videoComposer.rotateScreenRegions(litFrames, request.getSourceRotation(), frameSize[0], frameSize[1]);
            job.getWarnings().addAll(videoComposer.verifyScreenEdges(
                    masterVideo, new ArrayList<>(overlayVideos.keySet()), regionsForVerification, chromaKey, litFrames, videoDuration));
        }

        job.setStatus(JobStatus.REVIEW);
        touch(job, "prêt pour vérification");
    }

    private void runExportStage(Job job) {
        Path work = store.workDir(job.getId());
        Path output = store.outputDir(job.getId());
        Path master = work.resolve("master.mp4");
        if (!Files.isRegularFile(master)) {
            throw new IllegalStateException("No composed master found - the compose stage must finish first");
        }
        try {
            Files.createDirectories(output);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create " + output, e);
        }

        job.setStatus(JobStatus.PROCESSING);
        touch(job, "génération des exports");

        List<CompletableFuture<Void>> renders = new ArrayList<>();
        for (String platform : job.getPlatforms()) {
            renders.add(CompletableFuture.runAsync(() -> {
                Path out = output.resolve(platform + ".mp4");
                platformExporter.export(master, platform, out);
                job.getOutputFiles().put(platform, out.getFileName().toString());
            }, renderExecutor));
        }
        CompletableFuture.allOf(renders.toArray(new CompletableFuture[0])).join();

        job.setStatus(JobStatus.DONE);
        touch(job, "terminé");
    }

    /**
     * Whisper (and hand-typed timings) routinely place the last segment's end a fraction of a
     * second past the video's real duration - a rounding artifact, not a real authoring mistake.
     * Failing the whole job over that forced the user to hand-edit JSON every time it happened.
     * Clamping here (with a warning, same philosophy as SegmentFitEngine's own trim safety net)
     * means only a genuinely nonsensical segment (one that starts after the video already ended)
     * still needs a real fix - and that's surfaced as a warning too, not a silent drop.
     */
    private void clampSegmentsToVideoDuration(Job job, JobRequest request, double videoDuration) {
        List<SegmentSpec> kept = new ArrayList<>();
        for (SegmentSpec seg : request.getSegments()) {
            if (seg.getStart() >= videoDuration - 0.05) {
                job.getWarnings().add(String.format(java.util.Locale.ROOT,
                        "Segment %.1fs-%.1fs ignoré : il commence après la fin réelle de la vidéo (%.1fs)",
                        seg.getStart(), seg.getEnd(), videoDuration));
                continue;
            }
            if (seg.getStart() < 0) {
                seg.setStart(0);
            }
            if (seg.getEnd() > videoDuration) {
                double overrun = seg.getEnd() - videoDuration;
                if (overrun > 0.05) {
                    job.getWarnings().add(String.format(java.util.Locale.ROOT,
                            "Segment %.1fs-%.1fs raccourci à %.1fs pour tenir dans la durée réelle de la vidéo "
                            + "(dépassait de %.1fs)",
                            seg.getStart(), seg.getEnd(), videoDuration, overrun));
                }
                seg.setEnd(videoDuration);
            }
            kept.add(seg);
        }
        request.setSegments(kept);
    }

    private void touch(Job job, String stage) {
        job.setStage(stage);
        job.setUpdatedAt(Instant.now());
        store.save(job);
    }
}

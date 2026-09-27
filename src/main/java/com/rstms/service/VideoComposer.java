package com.rstms.service;

import com.rstms.config.AppProperties;
import com.rstms.model.BlurRegionSpec;
import com.rstms.model.ChromaKeySpec;
import com.rstms.model.EllipseZoneSpec;
import com.rstms.model.FinishingSpec;
import com.rstms.model.LightingEffectSpec;
import com.rstms.model.OverlayElementSpec;
import com.rstms.model.ScreenRegionSpec;
import com.rstms.model.TvLookSpec;
import com.rstms.video.FfmpegRunner;
import com.rstms.video.LightingMask;
import com.rstms.video.TvLook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Muxes the new audio onto the source video, optionally compositing up to three overlay videos
 * onto colored chroma-key screens (green/blue/red) in the base video, blurring one or more regions
 * for a time range, placing manual text/PNG overlay elements ("Incrustations"), and/or burning in
 * captions. The video is never re-cut - these only change pixels within the existing timeline. When
 * none of them are requested, the video stream is stream-copied (no re-encode, no quality loss);
 * otherwise a filter_complex chain is built and it's re-encoded once for every effect together.
 */
@Component
public class VideoComposer {

    private static final Logger log = LoggerFactory.getLogger(VideoComposer.class);

    /** Fixed processing order: green is the mandatory/primary screen, blue and red are optional
     * additional ones on the same base video (e.g. a second small screen). */
    private static final List<String> SCREEN_ORDER = List.of("green", "blue", "red");
    private static final Map<String, String> SCREEN_DEFAULT_HEX = Map.of(
            "green", "0x00FF00", "blue", "0x0000FF", "red", "0xFF0000");
    /** ffmpeg's despill filter only supports these two types - red has no entry, so a red screen
     * silently gets no despill node regardless of its despill setting (also disabled in the UI). */
    private static final Map<String, String> DESPILL_TYPE = Map.of("green", "green", "blue", "blue");

    /** The light's colour field is low-frequency by nature (a 6x6 average, blurred), so it is built at
     * 1/this resolution and upscaled last - identical to the eye, a fraction of the per-frame cost. */
    private static final int GLOW_FIELD_REDUCTION = 4;

    /** Everything buildVideoFilter needs about one screen's ambient light: where the lit box sits
     * in the frame, the pre-rendered mask images' ffmpeg input indexes (-1 = layer off) and the
     * colour-field tuning derived from the user's sliders. The eq/blur mappings below are mirrored
     * in the wizard's live preview (index.html: drawLightingGlowFrame / lightingEq) - keep them in step. */
    private static final class LightingPlan {
        final int x, y, w, h;
        final double eqBrightness, eqSaturation, blurSigmaPx;
        int glowMaskIndex = -1;
        int shadowMaskIndex = -1;

        LightingPlan(LightingMask.Shape shape, LightingEffectSpec light) {
            this.x = shape.x;
            this.y = shape.y;
            this.w = shape.w;
            this.h = shape.h;
            this.eqBrightness = -0.05 + 0.25 * light.getBrightness();
            this.eqSaturation = 1.0 + light.getBrightness();
            // Blur radius as a fraction of the lit box's mean size (2% .. 8%), so "softness" looks the
            // same at any video resolution.
            this.blurSigmaPx = (0.02 + 0.06 * (light.getSoftness() / 100.0)) * (shape.w + shape.h) / 2.0;
        }
    }

    /** Everything buildVideoFilter needs about one screen's "Rendu TV" (see TvLookSpec / TvLook): the
     * colour LUT file (null = colours untouched), blur sigma and grain strength (0 = off), and the
     * glass masks' box in the frame plus their ffmpeg input indexes (-1 = layer off). The preview
     * mirrors every one of these (index.html: layoutTvLookPreview) - keep them in step. */
    private static final class TvLookPlan {
        final int x, y, w, h;
        final double blurSigma;
        final int grain;
        Path lutFile;
        int darkMaskIndex = -1;
        int glareMaskIndex = -1;

        TvLookPlan(TvLook.Geometry geo, TvLookSpec tv) {
            this.x = geo.x;
            this.y = geo.y;
            this.w = geo.w;
            this.h = geo.h;
            this.blurSigma = tv.getSoftness() >= 0.05 ? tv.getSoftness() : 0;
            this.grain = (int) Math.round(tv.getGrain());
        }
    }

    private final FfmpegRunner ffmpeg;
    private final AppProperties props;

    public VideoComposer(FfmpegRunner ffmpeg, AppProperties props) {
        this.ffmpeg = ffmpeg;
        this.props = props;
    }

    /**
     * @param overlayVideos "green"/"blue"/"red" -> the clip composited onto that colored screen.
     *                      "green" (if present) plays at its own natural pace and is what
     *                      videoDuration is normally based on; "blue"/"red" loop/trim to match it
     *                      exactly like the base video does, regardless of their own native length.
     * @param screenRegions "green"/"blue"/"red" -> that screen's pixel box in the base video's own
     *                      frame - required for every color present in overlayVideos. The overlay
     *                      is fitted inside this exact box (aspect ratio preserved, letterboxed if
     *                      needed) and placed there; the base video's own frame size/format is
     *                      never touched.
     * @param chromaKeyTuning "green"/"blue"/"red" -> chroma-key tuning for that screen. A color with
     *                        no entry gets sensible defaults (ChromaKeySpec's own field defaults).
     * @param lightingTuning "green"/"blue"/"red" -> ambient TV-backlight glow/shadow tuning for that
     *                       screen. A color with no entry (or enabled=false) gets no lighting effect
     *                       at all and zero added filter-graph nodes.
     * @param tvLookTuning "green"/"blue"/"red" -> "Rendu TV" filters for that screen (TvLookSpec) -
     *                     same opt-in rule: no entry or enabled=false adds nothing.
     * @param overlayElements Manual text/PNG overlay elements ("Incrustations") - independent of
     *                        videoStyle, always composited if present.
     * @param overlayImages elementId -> the uploaded PNG file, for image-type overlayElements.
     * @param finishing Optional final whole-frame look/quality pass ("Finition & qualité") -
     *                  independent of videoStyle, applied last (after captions), null means no effect.
     * @param frameWidth/frameHeight The base video's own real, PRE-rotation dimensions - used to
     *                               keep a screen region's safety-margin expansion (see
     *                               ChromaKeySpec.edgeMargin) from stepping outside the actual
     *                               frame, and as the reference size for rotating region coordinates.
     * @param sourceRotation 0/90/180/270 - rotates the whole base video first, before any
     *                       region-based effect; see JobRequest.sourceRotation's own doc.
     * @param overlayRotations "green"/"blue"/"red" -> 0/90/180/270, rotating just that overlay
     *                         clip's own content before it's fitted into its screen region.
     */
    public Path compose(Path sourceVideo, Path masterAudio, Path captionsSrt, List<BlurRegionSpec> blurRegions,
                         Map<String, Path> overlayVideos, Map<String, ScreenRegionSpec> screenRegions,
                         Map<String, ChromaKeySpec> chromaKeyTuning, Map<String, LightingEffectSpec> lightingTuning,
                         Map<String, TvLookSpec> tvLookTuning, List<OverlayElementSpec> overlayElements,
                         Map<String, Path> overlayImages, FinishingSpec finishing,
                         int frameWidth, int frameHeight, int sourceRotation, Map<String, Integer> overlayRotations,
                         double videoDuration, Path targetMp4) {
        boolean hasBlur = blurRegions != null && !blurRegions.isEmpty();
        List<String> activeColors = new ArrayList<>();
        if (overlayVideos != null) {
            for (String color : SCREEN_ORDER) {
                if (overlayVideos.containsKey(color)) activeColors.add(color);
            }
        }
        boolean hasOverlay = !activeColors.isEmpty();
        List<OverlayElementSpec> elements = overlayElements != null ? overlayElements : List.of();
        boolean hasElements = !elements.isEmpty();
        boolean hasFinishing = finishing != null && (hasLookPreset(finishing) || finishing.isEnhance());
        boolean hasSourceRotation = normalizeRotation(sourceRotation) != 0;
        boolean hasAnyOverlayRotation = overlayRotations != null
                && overlayRotations.values().stream().anyMatch(r -> normalizeRotation(r) != 0);
        boolean needsVideoFilter = captionsSrt != null || hasBlur || hasOverlay || hasElements || hasFinishing
                || hasSourceRotation || hasAnyOverlayRotation;

        List<String> args = new ArrayList<>();
        if (hasOverlay) {
            // The base ("room"/template) video is the reusable, generic part - it's meant to loop
            // seamlessly and isn't noticed doing so. The (green) overlay is the actual content
            // being shown and is normally the longer of the two, so it dictates videoDuration
            // instead (see JobProcessor) while the base loops underneath to cover that full length.
            args.add("-stream_loop");
            args.add("-1");
        }
        args.add("-i");
        args.add(sourceVideo.toAbsolutePath().toString());
        args.add("-i");
        args.add(masterAudio.toAbsolutePath().toString());
        Map<String, Integer> inputIndexByColor = new LinkedHashMap<>();
        int nextIndex = 2;
        for (String color : activeColors) {
            // Only green plays at its own pace; additional screens loop/trim to match it, same
            // reasoning as the base video above.
            if (!color.equals("green")) {
                args.add("-stream_loop");
                args.add("-1");
            }
            args.add("-i");
            args.add(overlayVideos.get(color).toAbsolutePath().toString());
            inputIndexByColor.put(color, nextIndex++);
        }
        // Ambient light: one pre-rendered grayscale mask image per lit layer (glow/shadow) per screen,
        // fed in as looping still inputs exactly like the sticker PNGs below. The mask IS the light's
        // shape (see LightingMask) - static, so it's computed once here in Java instead of per frame
        // inside ffmpeg. Written next to the output (the job's own work dir).
        Map<String, LightingPlan> lightingPlans = new LinkedHashMap<>();
        Path maskDir = targetMp4.toAbsolutePath().getParent();
        for (String color : activeColors) {
            LightingEffectSpec light = lightingTuning != null ? lightingTuning.get(color) : null;
            if (light == null || !light.isEnabled() || !(light.isBacklightEnabled() || light.isShadowEnabled())) continue;
            // The light starts at the user-placed TV frame, NOT at the screen region the overlay is
            // placed in: that box is often dragged bigger than the TV (see LightingEffectSpec.frame).
            ScreenRegionSpec frame = light.getFrame() != null ? light.getFrame() : screenRegions.get(color);
            // No-light zones (objects in front of the TV), boxes and round ones - same coordinate
            // convention as the frame, so they rotate with it.
            List<ScreenRegionSpec> occluders = new ArrayList<>(light.getOccluders());
            List<EllipseZoneSpec> ellipses = new ArrayList<>(light.getEllipseOccluders());
            if (normalizeRotation(sourceRotation) != 0) {
                frame = rotateRegion(frame, sourceRotation, frameWidth, frameHeight);
                occluders.replaceAll(z -> rotateRegion(z, sourceRotation, frameWidth, frameHeight));
                ellipses.replaceAll(z -> rotateEllipse(z, sourceRotation, frameWidth, frameHeight));
            }
            LightingMask.Zones zones = LightingMask.Zones.of(occluders, ellipses);
            LightingMask.Shape shape = LightingMask.shape(frame, light.getSpread());
            LightingPlan plan = new LightingPlan(shape, light);
            try {
                if (light.isBacklightEnabled()) {
                    Path png = maskDir.resolve("lightmask_" + color + "_glow.png");
                    LightingMask.writeGrayPng(LightingMask.glowMask(shape, light.getBrightness(), light.getSoftness(), zones),
                            shape.w, shape.h, png);
                    args.add("-loop");
                    args.add("1");
                    args.add("-i");
                    args.add(png.toString());
                    plan.glowMaskIndex = nextIndex++;
                }
                if (light.isShadowEnabled()) {
                    Path png = maskDir.resolve("lightmask_" + color + "_shadow.png");
                    LightingMask.writeGrayPng(LightingMask.shadowMask(shape, light.getShadowIntensity(), zones),
                            shape.w, shape.h, png);
                    args.add("-loop");
                    args.add("1");
                    args.add("-i");
                    args.add(png.toString());
                    plan.shadowMaskIndex = nextIndex++;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not write the ambient-light mask for the " + color + " screen: " + e.getMessage(), e);
            }
            lightingPlans.put(color, plan);
        }
        // "Rendu TV": a colour LUT file plus two static glass masks (darkening, reflection) per screen,
        // written next to the output like the light masks and fed in the same way.
        Map<String, TvLookPlan> tvLookPlans = new LinkedHashMap<>();
        for (String color : activeColors) {
            TvLookSpec tv = tvLookTuning != null ? tvLookTuning.get(color) : null;
            if (tv == null || !tv.isEnabled()) continue;
            // The glass follows the TV's own outline: the frame placed for this effect, else the one
            // placed for the ambient light (the same TV), else the screen region.
            LightingEffectSpec light = lightingTuning != null ? lightingTuning.get(color) : null;
            ScreenRegionSpec frame = tv.getFrame() != null ? tv.getFrame()
                    : light != null && light.getFrame() != null ? light.getFrame() : screenRegions.get(color);
            if (normalizeRotation(sourceRotation) != 0) frame = rotateRegion(frame, sourceRotation, frameWidth, frameHeight);
            TvLook.Geometry geo = TvLook.geometry(frame);
            TvLookPlan plan = new TvLookPlan(geo, tv);
            try {
                double[][] colors = TvLook.colorMatrix(tv.getBrightness(), tv.getContrast(), tv.getSaturation(),
                        tv.getTemperature(), tv.getBlackLevel());
                if (!TvLook.isIdentity(colors)) {
                    plan.lutFile = maskDir.resolve("tvlook_" + color + ".cube");
                    TvLook.writeCubeLut(colors, plan.lutFile);
                }
                if (!geo.isDegenerate() && (tv.getVignette() > 0 || tv.getScanlines() > 0)) {
                    Path png = maskDir.resolve("tvlook_" + color + "_dark.png");
                    LightingMask.writeGrayPng(TvLook.darkMask(geo, tv.getVignette(), tv.getScanlines()), geo.w, geo.h, png);
                    args.add("-loop");
                    args.add("1");
                    args.add("-i");
                    args.add(png.toString());
                    plan.darkMaskIndex = nextIndex++;
                }
                if (!geo.isDegenerate() && tv.getGlare() > 0) {
                    Path png = maskDir.resolve("tvlook_" + color + "_glare.png");
                    LightingMask.writeGrayPng(TvLook.glareMask(geo, tv.getGlare(), tv.getGlarePosition()), geo.w, geo.h, png);
                    args.add("-loop");
                    args.add("1");
                    args.add("-i");
                    args.add(png.toString());
                    plan.glareMaskIndex = nextIndex++;
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not write the TV-look files for the " + color + " screen: " + e.getMessage(), e);
            }
            tvLookPlans.put(color, plan);
        }
        Map<String, Integer> inputIndexByElementId = new LinkedHashMap<>();
        for (OverlayElementSpec el : elements) {
            if (!"image".equals(el.getType())) continue;
            Path img = overlayImages != null ? overlayImages.get(el.getId()) : null;
            if (img == null) continue; // validated earlier in JobProcessor; defensive skip only
            // A looping still, not a single frame - "overlay ... enable=between(t,...)" needs a
            // frame available at arbitrary timestamps across the whole output, same reasoning as
            // the blue/red screen overlays' own -stream_loop -1 above.
            args.add("-loop");
            args.add("1");
            args.add("-i");
            args.add(img.toAbsolutePath().toString());
            inputIndexByElementId.put(el.getId(), nextIndex++);
        }

        if (needsVideoFilter) {
            args.add("-filter_complex");
            args.add(buildVideoFilter(captionsSrt, hasBlur ? blurRegions : List.of(),
                    activeColors, inputIndexByColor, screenRegions, chromaKeyTuning, lightingPlans, tvLookPlans,
                    elements, inputIndexByElementId, finishing, frameWidth, frameHeight,
                    sourceRotation, overlayRotations, videoDuration, props));
            args.add("-map");
            args.add("[vout]");
            args.add("-map");
            args.add("1:a:0");
            args.add("-c:v");
            args.add("libx264");
            args.add("-preset");
            args.add("medium");
            args.add("-crf");
            args.add("20");
        } else {
            args.add("-map");
            args.add("0:v:0");
            args.add("-map");
            args.add("1:a:0");
            args.add("-c:v");
            args.add("copy");
        }

        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add("192k");
        args.add("-shortest");
        args.add(targetMp4.toAbsolutePath().toString());

        String what = "composing master video";
        if (hasOverlay) what += " with " + activeColors.size() + " chroma-key overlay(s) (" + activeColors + ")";
        if (!tvLookPlans.isEmpty()) what += " with a TV look on " + tvLookPlans.keySet();
        if (hasBlur) what += " with " + blurRegions.size() + " blur region(s)";
        if (hasElements) what += " with " + elements.size() + " overlay element(s)";
        if (captionsSrt != null) what += " and burned captions";
        if (hasFinishing) what += " and a finishing pass";
        if (hasSourceRotation) what += " rotated " + normalizeRotation(sourceRotation) + "°";
        ffmpeg.ffmpeg(args, what);
        return targetMp4;
    }

    private static boolean hasLookPreset(FinishingSpec finishing) {
        String preset = finishing.getLookPreset();
        return preset != null && !preset.isBlank() && !preset.equals("none");
    }

    private static String buildVideoFilter(Path captionsSrt, List<BlurRegionSpec> blurRegions,
                                            List<String> activeColors, Map<String, Integer> inputIndexByColor,
                                            Map<String, ScreenRegionSpec> screenRegions,
                                            Map<String, ChromaKeySpec> chromaKeyTuning,
                                            Map<String, LightingPlan> lightingPlans,
                                            Map<String, TvLookPlan> tvLookPlans,
                                            List<OverlayElementSpec> overlayElements,
                                            Map<String, Integer> inputIndexByElementId,
                                            FinishingSpec finishing, int frameWidth, int frameHeight,
                                            int sourceRotation, Map<String, Integer> overlayRotations,
                                            double videoDuration, AppProperties props) {
        // Region coordinates (screens/blur/stickers) are always given relative to the ORIGINAL,
        // un-rotated frame - transform them to match BEFORE anything below uses them, using the
        // frame's PRE-rotation size as the reference, then switch frameWidth/frameHeight to the
        // POST-rotation (possibly swapped) size so every downstream clamp/placement is already
        // correct without needing to know rotation happened at all.
        if (normalizeRotation(sourceRotation) != 0) {
            Map<String, ScreenRegionSpec> rotatedRegions = new LinkedHashMap<>();
            for (Map.Entry<String, ScreenRegionSpec> e : screenRegions.entrySet()) {
                rotatedRegions.put(e.getKey(), rotateRegion(e.getValue(), sourceRotation, frameWidth, frameHeight));
            }
            screenRegions = rotatedRegions;

            List<BlurRegionSpec> rotatedBlur = new ArrayList<>();
            for (BlurRegionSpec r : blurRegions) {
                int[] nb = rotateRect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), sourceRotation, frameWidth, frameHeight);
                BlurRegionSpec copy = new BlurRegionSpec();
                copy.setStart(r.getStart());
                copy.setEnd(r.getEnd());
                copy.setX(nb[0]);
                copy.setY(nb[1]);
                copy.setWidth(nb[2]);
                copy.setHeight(nb[3]);
                copy.setStrength(r.getStrength());
                rotatedBlur.add(copy);
            }
            blurRegions = rotatedBlur;

            List<OverlayElementSpec> rotatedElements = new ArrayList<>();
            for (OverlayElementSpec el : overlayElements) {
                int[] nb = rotateRect(el.getX(), el.getY(), el.getWidth(), el.getHeight(), sourceRotation, frameWidth, frameHeight);
                OverlayElementSpec copy = new OverlayElementSpec();
                copy.setType(el.getType());
                copy.setId(el.getId());
                copy.setStart(el.getStart());
                copy.setEnd(el.getEnd());
                copy.setX(nb[0]);
                copy.setY(nb[1]);
                copy.setWidth(nb[2]);
                copy.setHeight(nb[3]);
                copy.setText(el.getText());
                copy.setStylePreset(el.getStylePreset());
                copy.setFontSize(el.getFontSize());
                copy.setColor(el.getColor());
                rotatedElements.add(copy);
            }
            overlayElements = rotatedElements;

            if (normalizeRotation(sourceRotation) == 90 || normalizeRotation(sourceRotation) == 270) {
                int swap = frameWidth;
                frameWidth = frameHeight;
                frameHeight = swap;
            }
        }

        StringBuilder filter = new StringBuilder();
        String cur = "0:v";
        String baseRotation = rotationFilterFragment(sourceRotation);
        if (!baseRotation.isEmpty()) {
            filter.append('[').append(cur).append(']').append(baseRotation).append("[rotated];");
            cur = "rotated";
        }

        if (!activeColors.isEmpty()) {
            // Chained once per active color, all confined to that screen's own bounding box - never
            // the whole frame. Each screen is a general quadrilateral (not necessarily an
            // axis-aligned rectangle), since a screen filmed at an angle appears as a skewed shape,
            // not a straight-on 0/90 rectangle. Per color: (1) scale the overlay to FIT inside the
            // quad's bounding box, aspect preserved, padding leftover space with black; (2)
            // perspective-warp that fitted rectangle so its corners land exactly on the quad's own
            // corners (ffmpeg's perspective filter takes destination corners in TL,TR,BL,BR order);
            // (3) crop the bounding box out of the running composite and key its color there -
            // pixels outside the quad but inside its bounding box aren't the key color in real
            // footage, so chromakey naturally leaves them alone; (3b) optionally despill the keyed
            // crop to remove color fringing at the edges; (4) drop the warped overlay in behind that
            // keyed crop; (5) paste the result back at the bounding box's origin. Step 3 keys the
            // *running composite*, not a fresh base copy, so an earlier pass's content survives -
            // untouched pixels there are still identical to the original base. Reads [cur] (which
            // may already be the rotated frame, not literally "0:v") so screens are always placed
            // relative to whatever orientation is actually being delivered.
            filter.append('[').append(cur).append(']')
                  .append(String.format(Locale.ROOT, "trim=0:%.3f,setpts=PTS-STARTPTS[gsbase];", videoDuration));
            cur = "gsbase";
            int step = 0;
            for (String color : activeColors) {
                int idx = inputIndexByColor.get(color);
                ScreenRegionSpec region = screenRegions.get(color);
                ChromaKeySpec tuning = chromaKeyTuning != null && chromaKeyTuning.get(color) != null
                        ? chromaKeyTuning.get(color) : new ChromaKeySpec();
                // Grown a few percent past the traced corners (see ChromaKeySpec.edgeMargin) before
                // any cropping/keying/warping - a hand-traced region is almost always a pixel or few
                // short of the real screen edge, and this is what eliminates the leftover strip of
                // raw, un-keyed green that would otherwise show at that boundary in the final render.
                ScreenRegionSpec expandedRegion = expandRegion(region, Math.max(0, tuning.getEdgeMargin()) / 100.0);
                int[] bbox = boundingBox(expandedRegion, frameWidth, frameHeight);
                int bx = bbox[0], by = bbox[1], bw = bbox[2], bh = bbox[3];
                String hex = tuning.getColor() != null ? tuning.getColor() : SCREEN_DEFAULT_HEX.get(color);
                double similarity = tuning.getSimilarity();
                double blend = tuning.getBlend();

                // Ambient TV-backlight glow/shadow (see LightingEffectSpec / LightingMask). The plan -
                // the light's own placement and its two pre-rendered mask images - was built once in
                // compose() from the user-placed TV frame, deliberately NOT from this screen's crop box
                // above: that box only decides what gets chroma-keyed and pasted back.
                LightingPlan lp = lightingPlans != null ? lightingPlans.get(color) : null;
                boolean glowOn = lp != null && lp.glowMaskIndex >= 0;
                boolean shadowOn = lp != null && lp.shadowMaskIndex >= 0;

                String ovlFitted = "gsovlfit" + step;
                String ovlWarped = "gsovlwarp" + step;
                String regionCrop = "gsregioncrop" + step;
                String regionKeyed = "gsregionkeyed" + step;
                String regionDespilled = "gsregiondespill" + step;
                String regionComp = "gsregioncomp" + step;
                String next = "gscomposite" + step;

                // This overlay's own rotation (independent of the base video's) - applied first, so
                // scale/pad below sees the already-rotated content and fits IT (not the original
                // orientation) into the screen's bounding box. No-op (empty prefix) when 0°.
                String ovlRotation = rotationFilterFragment(
                        overlayRotations != null ? overlayRotations.getOrDefault(color, 0) : 0);
                String ovlRotationPrefix = ovlRotation.isEmpty() ? "" : ovlRotation + ",";

                // Glow source: sampled from the RAW rotated/trimmed overlay - deliberately BEFORE
                // scale+pad below, not after. Whenever the overlay's aspect ratio (after its own
                // rotation) differs from the screen box's, force_original_aspect_ratio=decrease pads
                // the leftover space with solid BLACK to reach bw x bh exactly; sampling the glow
                // from that PADDED canvas would dilute the 6x6 color average with black bars that
                // are never actually lit content. (Not the cause of the user's own job - its 270°
                // rotation happens to make a 720x1280 clip nearly match the 760x426 box - but a
                // portrait clip in a landscape screen without a rotation would be hit hard.)
                String ovlFittedForWarp = ovlFitted;
                String glowLayer = null;
                if (glowOn) {
                    String ovlRaw = "gsovlraw" + step;
                    String ovlRawFitSrc = "gsovlraw" + step + "fit";
                    String ovlRawGlowSrc = "gsovlraw" + step + "glow";
                    // Explicit split (not implicit reuse) for the same reason the [cur] split below
                    // is explicit: this label can trace back through a rotation transpose
                    // (overlayRotations), and reusing a transpose-derived label across two consumers
                    // without a split is the exact silent-rotation-bug class documented on the [cur]
                    // split further down.
                    filter.append('[').append(idx).append(":v]")
                          .append(ovlRotationPrefix)
                          .append(String.format(Locale.ROOT, "trim=0:%.3f,setpts=PTS-STARTPTS", videoDuration))
                          .append('[').append(ovlRaw).append("];");
                    filter.append('[').append(ovlRaw).append(']')
                          .append("split=2[").append(ovlRawFitSrc).append("][").append(ovlRawGlowSrc).append("];");
                    // pad's target uses max(iw,w)/max(ih,h) rather than the bare w/h: scale's own
                    // aspect-preserving rounding can overshoot the nominal target by a pixel (a known
                    // ffmpeg quirk), and padding to an exact size smaller than what scale just
                    // produced fails outright ("Padded dimensions cannot be smaller than input
                    // dimensions").
                    filter.append('[').append(ovlRawFitSrc).append(']')
                          .append(String.format(Locale.ROOT,
                                  "scale=%d:%d:force_original_aspect_ratio=decrease,"
                                  + "pad=max(iw\\,%d):max(ih\\,%d):(ow-iw)/2:(oh-ih)/2:black",
                                  bw, bh, bw, bh))
                          .append('[').append(ovlFitted).append("];");

                    // COLOR of the light: shrink the raw frame to 6x6 (an exact area average - the
                    // frame's dominant colors/brightness, per frame, computed natively by ffmpeg with
                    // no separate Java-side pixel analysis), grow it back to a smooth field, blur the
                    // block edges away. Only the SHAPE (where/how strongly the light shows) is a
                    // separate static mask image (LightingMask) merged in as alpha afterwards. The field
                    // is built at 1/GLOW_FIELD_REDUCTION resolution and upscaled last: it is inherently
                    // low-frequency, so this is visually identical but makes a 4K lit area affordable.
                    int cw = Math.max(2, (lp.w / GLOW_FIELD_REDUCTION) & ~1);
                    int ch = Math.max(2, (lp.h / GLOW_FIELD_REDUCTION) & ~1);
                    String glowColor = "gsglowcolor" + step;
                    String glowMask = "gsglowmask" + step;
                    glowLayer = "gsglow" + step;
                    filter.append('[').append(ovlRawGlowSrc).append(']')
                          .append(String.format(Locale.ROOT,
                                  "scale=6:6:flags=area,scale=%d:%d:flags=bilinear,"
                                  + "eq=brightness=%.3f:saturation=%.3f,gblur=sigma=%.2f,"
                                  + "scale=%d:%d:flags=bilinear,format=yuv420p",
                                  cw, ch, lp.eqBrightness, lp.eqSaturation,
                                  lp.blurSigmaPx / GLOW_FIELD_REDUCTION, lp.w, lp.h))
                          .append('[').append(glowColor).append("];");
                    // Bounded like every other looping input in this graph (trim), so an unbounded
                    // mask stream can never keep the video running past the intended duration.
                    filter.append('[').append(lp.glowMaskIndex).append(":v]")
                          .append(String.format(Locale.ROOT, "format=gray,trim=0:%.3f,setpts=PTS-STARTPTS", videoDuration))
                          .append('[').append(glowMask).append("];");
                    filter.append('[').append(glowColor).append("][").append(glowMask).append(']')
                          .append("alphamerge[").append(glowLayer).append("];");
                } else {
                    // No glow this color: plain fit+pad straight from the source, same as before this
                    // stage existed - no raw-content split needed since nothing else consumes the
                    // pre-pad frame.
                    filter.append('[').append(idx).append(":v]")
                          .append(ovlRotationPrefix)
                          .append(String.format(Locale.ROOT,
                                  "trim=0:%.3f,setpts=PTS-STARTPTS,scale=%d:%d:force_original_aspect_ratio=decrease,"
                                  + "pad=max(iw\\,%d):max(ih\\,%d):(ow-iw)/2:(oh-ih)/2:black",
                                  videoDuration, bw, bh, bw, bh))
                          .append('[').append(ovlFitted).append("];");
                }

                // "Rendu TV", picture part: the colour map (a LUT of TvLook.colorMatrix) and the camera's
                // focus softness, on the fitted overlay - before the warp, like the preview's CSS filter
                // on its (not yet transformed) container. lut3d works in RGB: the conversions there and
                // back are explicit with accurate_rnd, because the default ones truncate and darkened
                // the picture by 0.76 level on average (measured; with accurate_rnd: 0.007). Ends in
                // yuv420p either way, so the grain below lands on luma, not on whatever plane is first.
                TvLookPlan tp = tvLookPlans != null ? tvLookPlans.get(color) : null;
                if (tp != null && (tp.lutFile != null || tp.blurSigma > 0)) {
                    String ovlTv = "gsovltv" + step;
                    List<String> tvStages = new ArrayList<>();
                    if (tp.lutFile != null) {
                        tvStages.add("scale=flags=accurate_rnd");
                        tvStages.add("format=gbrp");
                        tvStages.add("lut3d=file='" + escapeForFilter(tp.lutFile.toAbsolutePath().toString()) + "':interp=tetrahedral");
                    }
                    if (tp.blurSigma > 0) tvStages.add(String.format(Locale.ROOT, "gblur=sigma=%.3f", tp.blurSigma));
                    if (tp.lutFile != null) tvStages.add("scale=flags=accurate_rnd");
                    tvStages.add("format=yuv420p");
                    filter.append('[').append(ovlFittedForWarp).append(']').append(String.join(",", tvStages))
                          .append('[').append(ovlTv).append("];");
                    ovlFittedForWarp = ovlTv;
                }

                filter.append('[').append(ovlFittedForWarp).append(']')
                      // sense=destination is essential here and NOT the filter's default (which is
                      // "source" - an inverse mapping that samples the source at these points rather
                      // than sending the source's own corners to them). Verified empirically: with
                      // the default, corners warp in the mirror-opposite direction from intended.
                      .append(String.format(Locale.ROOT,
                              "perspective=x0=%.2f:y0=%.2f:x1=%.2f:y1=%.2f:x2=%.2f:y2=%.2f:x3=%.2f:y3=%.2f:sense=destination",
                              expandedRegion.getTopLeft().getX() - bx, expandedRegion.getTopLeft().getY() - by,
                              expandedRegion.getTopRight().getX() - bx, expandedRegion.getTopRight().getY() - by,
                              expandedRegion.getBottomLeft().getX() - bx, expandedRegion.getBottomLeft().getY() - by,
                              expandedRegion.getBottomRight().getX() - bx, expandedRegion.getBottomRight().getY() - by))
                      .append('[').append(ovlWarped).append("];");

                // "Rendu TV", glass and camera part, on the warped overlay (frame coordinates, bw x bh at
                // bx,by) - i.e. still BEFORE it goes behind the keyed green, so it only ever shows where
                // the TV is green: darkening (black through its mask), reflection (white through its
                // mask), then the camera's grain over all of it.
                String ovlForKey = ovlWarped;
                if (tp != null) {
                    if (tp.darkMaskIndex >= 0) {
                        ovlForKey = appendGlassLayer(filter, ovlForKey, "black", tp.darkMaskIndex, tp, bx, by,
                                videoDuration, "gstvdark" + step);
                    }
                    if (tp.glareMaskIndex >= 0) {
                        ovlForKey = appendGlassLayer(filter, ovlForKey, "white", tp.glareMaskIndex, tp, bx, by,
                                videoDuration, "gstvglare" + step);
                    }
                    if (tp.grain > 0) {
                        String grained = "gstvgrain" + step;
                        filter.append('[').append(ovlForKey).append(']')
                              .append(String.format(Locale.ROOT, "noise=c0s=%d:c0f=t", tp.grain))
                              .append('[').append(grained).append("];");
                        ovlForKey = grained;
                    }
                }

                // Explicit split (matching the same pattern the blur loop below already uses) rather
                // than implicitly referencing [cur] twice (once here for crop, again below as the
                // paste-back target) - empirically required, not just tidier: when [cur] traces back
                // through a rotation transpose (see sourceRotation), letting ffmpeg's parser reuse
                // that label directly for both consumers silently produced an UN-rotated final output
                // despite every intermediate stage individually reporting the correct rotated
                // dimensions - reproduced and root-caused by bisecting the filtergraph down to this
                // exact reuse, and confirmed fixed by this same explicit split, before relying on it.
                String curForCrop = "gscur" + step + "crop";
                String curForPasteback = "gscur" + step + "paste";
                filter.append('[').append(cur).append(']')
                      .append("split=2[").append(curForCrop).append("][").append(curForPasteback).append("];");

                filter.append('[').append(curForCrop).append(']')
                      .append(String.format(Locale.ROOT, "crop=%d:%d:%d:%d", bw, bh, bx, by))
                      .append('[').append(regionCrop).append("];");
                filter.append('[').append(regionCrop).append(']')
                      .append(String.format(Locale.ROOT, "chromakey=%s:%.3f:%.3f", hex, similarity, blend))
                      .append('[').append(regionKeyed).append("];");

                String keyedForOverlay = regionKeyed;
                String despillType = DESPILL_TYPE.get(color);
                if (despillType != null && tuning.getDespill() > 0) {
                    filter.append('[').append(regionKeyed).append(']')
                          .append(String.format(Locale.ROOT, "despill=type=%s:mix=%.3f:expand=0",
                                  despillType, tuning.getDespill()))
                          .append('[').append(regionDespilled).append("];");
                    keyedForOverlay = regionDespilled;
                }

                String regionCompRaw = "gsregioncompraw" + step;
                filter.append('[').append(ovlForKey).append("][").append(keyedForOverlay).append(']')
                      .append("overlay=shortest=1[").append(regionCompRaw).append("];");

                // A small (3px) feather on this box's OWN outer edge before it gets pasted back -
                // standard professional-keying practice, and it does double duty here: it masks any
                // last pixel-or-two of chroma-key/despill imprecision right at the traced boundary,
                // AND it softens what would otherwise be a razor-hard cut between two independently
                // processed regions - which is exactly the kind of edge H.264 rings/haloes around at
                // normal CRF, producing a thin bright seam line even when the underlying compositing
                // is correct (confirmed against real footage: the seam was present with the lighting
                // effect fully disabled too, so it predates and is independent of it). Applied to the
                // WHOLE regionComp box uniformly, not just the chroma-keyed reveal window, since the
                // seam is at the outer crop boundary, not the inner key boundary.
                int feather = 3;
                filter.append('[').append(regionCompRaw).append(']')
                      .append(String.format(Locale.ROOT,
                              "format=yuva420p,geq=lum='lum(X\\,Y)':cb='cb(X\\,Y)':cr='cr(X\\,Y)':"
                              + "a='255*min(1\\,min(min(X/%d\\,(%d-X)/%d)\\,min(Y/%d\\,(%d-Y)/%d)))'",
                              feather, bw, feather, feather, bh, feather))
                      .append('[').append(regionComp).append("];");

                filter.append('[').append(curForPasteback).append("][").append(regionComp).append(']')
                      .append(String.format(Locale.ROOT, "overlay=%d:%d", bx, by))
                      .append('[').append(next).append("];");
                cur = next;

                // Ambient light goes on AFTER the paste-back, over the finished frame. Under it (as
                // an earlier version did) the paste-back's opaque copy of the original pixels - the
                // whole edgeMargin-grown box, i.e. the TV plus a strip of wall - erased the light
                // exactly where it should begin, so it visibly started well away from the TV's border
                // and never matched the wizard preview. The light shape itself (LightingMask) is
                // already zero inside the TV frame and fades outward from its edge, so drawing it
                // over the whole frame can never touch the screen content.
                // Shadow first (darkens the surroundings), glow over it. Layers are plain alpha
                // overlays: fully transparent pixels leave the frame bit-exact, so there is no visible
                // box edge even where the lit area is clipped by the frame border.
                if (shadowOn) {
                    String shadowBlack = "gsshadowblack" + step;
                    String shadowMask = "gsshadowmask" + step;
                    String shadowLayer = "gsshadow" + step;
                    String withShadow = "gscomposite" + step + "shadow";
                    filter.append(String.format(Locale.ROOT, "color=c=black:s=%dx%d:r=25:d=%.3f,format=yuv420p",
                                  lp.w, lp.h, videoDuration))
                          .append('[').append(shadowBlack).append("];");
                    filter.append('[').append(lp.shadowMaskIndex).append(":v]")
                          .append(String.format(Locale.ROOT, "format=gray,trim=0:%.3f,setpts=PTS-STARTPTS", videoDuration))
                          .append('[').append(shadowMask).append("];");
                    filter.append('[').append(shadowBlack).append("][").append(shadowMask).append(']')
                          .append("alphamerge[").append(shadowLayer).append("];");
                    filter.append('[').append(cur).append("][").append(shadowLayer).append(']')
                          .append(String.format(Locale.ROOT, "overlay=%d:%d", lp.x, lp.y))
                          .append('[').append(withShadow).append("];");
                    cur = withShadow;
                }
                if (glowOn) {
                    String withGlow = "gscomposite" + step + "glow";
                    filter.append('[').append(cur).append("][").append(glowLayer).append(']')
                          .append(String.format(Locale.ROOT, "overlay=%d:%d", lp.x, lp.y))
                          .append('[').append(withGlow).append("];");
                    cur = withGlow;
                }
                step++;
            }
        }

        int i = 0;
        for (BlurRegionSpec r : blurRegions) {
            String base = "vb" + i + "base";
            String crop = "vb" + i + "crop";
            String blurred = "vb" + i + "blur";
            String next = "vb" + i + "out";

            filter.append('[').append(cur).append(']')
                  .append("split=2[").append(base).append("][").append(crop).append("];");
            // boxblur's chroma-plane radius must stay under 15 even though luma has no such cap -
            // pass it separately rather than reusing the luma radius for both.
            int chromaRadius = Math.min(r.getStrength(), 14);
            filter.append('[').append(crop).append(']')
                  .append(String.format(Locale.ROOT, "crop=%d:%d:%d:%d,boxblur=%d:1:%d:1",
                          r.getWidth(), r.getHeight(), r.getX(), r.getY(), r.getStrength(), chromaRadius))
                  .append('[').append(blurred).append("];");
            filter.append('[').append(base).append("][").append(blurred).append(']')
                  .append(String.format(Locale.ROOT, "overlay=%d:%d:enable='between(t,%.3f,%.3f)'",
                          r.getX(), r.getY(), r.getStart(), r.getEnd()))
                  .append('[').append(next).append("];");
            cur = next;
            i++;
        }

        // Manual text/PNG overlay elements ("Incrustations") - deliberately placed AFTER blur but
        // BEFORE the dialogue-caption burn-in below: dialogue captions are what a viewer relies on
        // for comprehension and are already tuned to a bottom-safe zone, so a user-placed sticker
        // must never be able to cover them.
        int si = 0;
        for (OverlayElementSpec el : overlayElements) {
            double end = el.getEnd() > 0 ? el.getEnd() : videoDuration;
            String next = "stkout" + si;
            if ("image".equals(el.getType())) {
                Integer idx = inputIndexByElementId.get(el.getId());
                if (idx == null) { si++; continue; } // validated earlier; defensive skip only
                String fit = "stkfit" + si;
                filter.append('[').append(idx).append(":v]")
                      .append(String.format(Locale.ROOT, "scale=%d:%d,format=rgba", el.getWidth(), el.getHeight()))
                      .append('[').append(fit).append("];");
                filter.append('[').append(cur).append("][").append(fit).append(']')
                      .append(String.format(Locale.ROOT, "overlay=%d:%d:enable='between(t,%.3f,%.3f)'",
                              el.getX(), el.getY(), el.getStart(), end))
                      .append('[').append(next).append("];");
            } else {
                filter.append('[').append(cur).append(']')
                      .append(drawtextParamsFor(el, props))
                      .append(String.format(Locale.ROOT, ":enable='between(t,%.3f,%.3f)'", el.getStart(), end))
                      .append('[').append(next).append("];");
            }
            cur = next;
            si++;
        }

        if (captionsSrt != null) {
            String escaped = escapeForFilter(captionsSrt.toAbsolutePath().toString());
            // Style tuned and verified by rendering real frames at several sizes/positions (not
            // guessed): bold Arial - the old style had no Bold flag, so libass drew a plain regular
            // weight that looked thin/cheap next to real TikTok/Reels/Shorts caption styling. Values
            // below are calibrated for ffmpeg's fixed 384x288 SRT->ASS reference canvas (confirmed by
            // testing that this - not the real video resolution - is what Fontsize/MarginV scale
            // against here; passing an explicit original_size actually broke positioning). MarginV=90
            // keeps caption text well clear of TikTok's ~320px and Instagram Reels' ~420-480px
            // bottom UI overlay zones (engagement icons, caption line, audio credit) after scaling.
            filter.append('[').append(cur).append(']')
                  .append("subtitles='").append(escaped)
                  .append("':force_style='Fontname=Arial,Bold=1,Fontsize=18,PrimaryColour=&H00FFFFFF,")
                  .append("OutlineColour=&H00000000,BorderStyle=1,Outline=2,Shadow=1,Alignment=2,MarginV=90'")
                  .append("[captioned];");
            cur = "captioned";
        }

        // Final whole-frame finishing pass ("Finition & qualité") - deliberately LAST, after
        // captions/stickers/blur/screens: it's meant to unify the look of the *entire* delivered
        // frame (including any burned-in text), not just the composited region.
        boolean hasLook = finishing != null && hasLookPreset(finishing);
        boolean hasEnhance = finishing != null && finishing.isEnhance();
        if (hasLook || hasEnhance) {
            filter.append('[').append(cur).append(']')
                  .append(buildFinishingFilter(finishing))
                  .append("[vout]");
        } else {
            filter.append('[').append(cur).append(']').append("null[vout]");
        }

        return filter.toString();
    }

    /**
     * Concrete ffmpeg chain for the "look" preset (grain/color/vignette combos meant to make a
     * composite read as one consistently-filmed shot) and the "enhance" pass (denoise, sharpen, a
     * small contrast/saturation lift), chained together when both are requested. Every number below
     * was chosen and eyeballed on real rendered frames, not copied from a preset library.
     */
    private static String buildFinishingFilter(FinishingSpec finishing) {
        List<String> stages = new ArrayList<>();
        String preset = finishing.getLookPreset();
        if ("match_grain".equals(preset)) {
            // Light, mostly-luma grain plus a barely-there gamma/contrast nudge - meant to be the
            // subtle default that unifies a clean composite with grainier surrounding footage
            // without being a visible "effect" on its own.
            stages.add("noise=alls=8:allf=t");
            stages.add("eq=contrast=1.03:saturation=1.05:gamma=0.98");
        } else if ("cinematic".equals(preset)) {
            // A mild teal-shadows/warm-highlights push (independent red/blue gamma) plus vignette
            // and a touch of grain - a common, recognizable "filmic" combination.
            stages.add("eq=contrast=1.08:saturation=1.12:gamma_r=0.97:gamma_b=1.03");
            stages.add("vignette=PI/5");
            stages.add("noise=alls=6:allf=t");
        } else if ("handheld_vignette".equals(preset)) {
            // No color grade - just a stronger vignette and coarser grain, for a raw/on-the-fly feel.
            stages.add("vignette=PI/4");
            stages.add("noise=alls=10:allf=t+u");
        } else if ("premium".equals(preset)) {
            // Deliberately the only preset with NO grain/noise at all - "shot on nice equipment"
            // reads as clean, not textured. gamma (not brightness) lifts shadows without blowing out
            // highlights, so the same settings stay flattering on both bright and dim source footage
            // rather than needing a day/night choice; independent red/blue gamma adds a gentle,
            // uniform warmth (unlike "cinematic"'s teal-shadow/warm-highlight split). A light unsharp
            // pass (well below what the separate Enhance toggle's own ceiling reaches) is baked in as
            // part of the look itself - "nice camera" reads as crisp, not just colorful - and a very
            // subtle vignette for natural lens falloff rather than a stylistic frame.
            stages.add("eq=contrast=1.10:saturation=1.15:gamma=1.04:gamma_r=1.02:gamma_b=0.97");
            stages.add("unsharp=5:5:0.4:5:5:0.1");
            stages.add("vignette=PI/8");
        }
        if (finishing.isEnhance()) {
            double t = Math.max(0, Math.min(1, finishing.getEnhanceIntensity()));
            // Order matters: denoise first (so sharpening doesn't amplify sensor/compression noise),
            // then sharpen edges, then a small final contrast/saturation lift. All four numbers scale
            // linearly with intensity from a "barely there" floor to a "clearly enhanced" ceiling.
            stages.add(String.format(Locale.ROOT, "hqdn3d=%.2f:%.2f:%.2f:%.2f",
                    1.0 + 2.0 * t, 0.8 + 1.5 * t, 2.0 + 3.0 * t, 1.5 + 2.5 * t));
            stages.add(String.format(Locale.ROOT, "unsharp=5:5:%.2f:5:5:%.2f", 0.3 + 1.2 * t, 0.1 + 0.4 * t));
            stages.add(String.format(Locale.ROOT, "eq=contrast=%.3f:saturation=%.3f", 1.0 + 0.08 * t, 1.0 + 0.12 * t));
        }
        return String.join(",", stages);
    }

    /**
     * Maps each curated style preset to concrete drawtext parameters. fontcolor/boxcolor accept the
     * element's own "color" quick-tweak (validated as plain #RRGGBB hex by JobValidator - safe to
     * use unescaped here) when set, falling back to the preset's own default otherwise.
     * expansion=none disables drawtext's own "%{...}" expansion syntax so arbitrary user text is
     * always shown literally rather than occasionally being parsed as a directive.
     */
    private static String drawtextParamsFor(OverlayElementSpec el, AppProperties props) {
        String fontfile = escapeForFilter(props.getPipeline().getDrawtextFontfileBold());
        String text = escapeDrawtext(el.getText() != null ? el.getText() : "");
        String color = el.getColor() != null && !el.getColor().isBlank() ? el.getColor() : null;
        int fontSize = el.getFontSize() > 0 ? el.getFontSize() : 42;
        String preset = el.getStylePreset() != null ? el.getStylePreset() : "bold_outline";

        StringBuilder sb = new StringBuilder("drawtext=fontfile='").append(fontfile)
                .append("':text='").append(text).append("':expansion=none:fontsize=").append(fontSize);
        switch (preset) {
            case "highlight_box":
                sb.append(":x=").append(el.getX()).append(":y=").append(el.getY())
                  .append(":fontcolor=white:box=1:boxcolor=").append(color != null ? color : "0x1E90FF")
                  .append("@0.85:boxborderw=14");
                break;
            case "bottom_banner":
                // Deliberately overrides the dragged x with ffmpeg's own centering expression - a
                // real caption banner is horizontally centered; only the vertical position (y) is
                // left under the user's control.
                sb.append(":x=(w-text_w)/2:y=").append(el.getY())
                  .append(":fontcolor=").append(color != null ? color : "white")
                  .append(":box=1:boxcolor=black@0.6:boxborderw=20");
                break;
            case "neon_glow": {
                // A faked glow via colored border+shadow using the same base color as the text
                // itself - there's no real blur/bloom pass on the text layer, this is an
                // approximation, not true glow.
                String glow = color != null ? color : "0x39FF14";
                sb.append(":x=").append(el.getX()).append(":y=").append(el.getY())
                  .append(":fontcolor=").append(glow)
                  .append(":borderw=3:bordercolor=").append(glow).append("@0.55")
                  .append(":shadowx=0:shadowy=0:shadowcolor=").append(glow).append("@0.7");
                break;
            }
            case "bold_outline":
            default:
                sb.append(":x=").append(el.getX()).append(":y=").append(el.getY())
                  .append(":fontcolor=").append(color != null ? color : "white")
                  .append(":borderw=4:bordercolor=black@0.85");
                break;
        }
        return sb.toString();
    }

    /** ffmpeg's subtitles filter parses its path with its own mini-syntax - colons and backslashes need escaping. */
    /** One "Rendu TV" glass layer: a solid colour (black = darkening, white = reflection) whose
     * opacity is a pre-rendered mask (TvLook) covering the TV frame's box, laid over [base] (the
     * warped overlay, whose own origin is bx,by in the frame). Same construction as the lighting's
     * shadow layer: fully transparent pixels leave the picture bit-exact. Returns the output label. */
    private static String appendGlassLayer(StringBuilder filter, String base, String colorName, int maskIndex,
                                           TvLookPlan tp, int bx, int by, double videoDuration, String label) {
        String solid = label + "solid", mask = label + "mask", layer = label + "layer";
        filter.append(String.format(Locale.ROOT, "color=c=%s:s=%dx%d:r=25:d=%.3f,format=yuv420p",
                      colorName, tp.w, tp.h, videoDuration))
              .append('[').append(solid).append("];");
        filter.append('[').append(maskIndex).append(":v]")
              .append(String.format(Locale.ROOT, "format=gray,trim=0:%.3f,setpts=PTS-STARTPTS", videoDuration))
              .append('[').append(mask).append("];");
        filter.append('[').append(solid).append("][").append(mask).append("]alphamerge[").append(layer).append("];");
        filter.append('[').append(base).append("][").append(layer).append(']')
              .append(String.format(Locale.ROOT, "overlay=%d:%d", tp.x - bx, tp.y - by))
              .append('[').append(label).append("];");
        return label;
    }

    private static String escapeForFilter(String path) {
        return path.replace("\\", "/").replace(":", "\\:");
    }

    /** Escapes free-form user text for a single-quoted ffmpeg filtergraph value: backslashes and
     * colons the same way escapeForFilter does for paths, plus single quotes (a path never has
     * one, but user-typed caption/sticker text routinely does, e.g. "Don't miss this") using
     * ffmpeg's documented close-escape-reopen sequence. */
    private static String escapeDrawtext(String text) {
        return text.replace("\\", "\\\\").replace(":", "\\:").replace("'", "'\\''");
    }

    /** The smallest axis-aligned pixel box fully containing all four (possibly non-rectangular)
     * corners - floor/ceil rather than plain rounding so the box never clips a fractional pixel,
     * then clamped to the actual frame (a safety-margin-expanded region - see expandRegion - can
     * otherwise step past the edge, which ffmpeg's crop filter rejects outright). frameWidth/
     * frameHeight <= 0 skips clamping on that axis (defensive only - real callers always know it). */
    private static int[] boundingBox(ScreenRegionSpec r, int frameWidth, int frameHeight) {
        double minX = Math.min(Math.min(r.getTopLeft().getX(), r.getTopRight().getX()),
                Math.min(r.getBottomLeft().getX(), r.getBottomRight().getX()));
        double minY = Math.min(Math.min(r.getTopLeft().getY(), r.getTopRight().getY()),
                Math.min(r.getBottomLeft().getY(), r.getBottomRight().getY()));
        double maxX = Math.max(Math.max(r.getTopLeft().getX(), r.getTopRight().getX()),
                Math.max(r.getBottomLeft().getX(), r.getBottomRight().getX()));
        double maxY = Math.max(Math.max(r.getTopLeft().getY(), r.getTopRight().getY()),
                Math.max(r.getBottomLeft().getY(), r.getBottomRight().getY()));
        // The origin is snapped DOWN to even (the box grows by that pixel rather than shifting): on
        // yuv420p both crop and overlay silently round an odd x/y down to even, so an odd origin put
        // the keyed crop, the paste-back and everything positioned relative to it (the warped video,
        // the "Rendu TV" glass layers) one pixel up/left of where it was computed - measured on a
        // render, the panel lines landed one row high.
        int bx = Math.floorDiv((int) Math.floor(minX), 2) * 2;
        int by = Math.floorDiv((int) Math.floor(minY), 2) * 2;
        int bw = (int) Math.ceil(maxX) - bx;
        int bh = (int) Math.ceil(maxY) - by;

        if (bx < 0) { bw += bx; bx = 0; }
        if (by < 0) { bh += by; by = 0; }
        if (frameWidth > 0 && bx + bw > frameWidth) bw = frameWidth - bx;
        if (frameHeight > 0 && by + bh > frameHeight) bh = frameHeight - by;
        bw = Math.max(2, bw);
        bh = Math.max(2, bh);

        // libx264's default yuv420p output requires even width/height (2x2 chroma subsampling) -
        // an odd bounding box dimension doesn't fail cleanly with a clear message, it surfaces deep
        // in the scale+pad step as a cryptic "Padded dimensions cannot be smaller than input
        // dimensions" once the encoder starts negotiating formats. Round down (never up) so the box
        // never grows past whatever it was already computed to fit within.
        if (bw % 2 != 0) bw--;
        if (bh % 2 != 0) bh--;
        return new int[]{bx, by, bw, bh};
    }

    /** Scales all 4 corners outward from the region's own center by (1+marginFraction) - see
     * ChromaKeySpec.edgeMargin's own doc for why this exists. Works correctly for a skewed quad,
     * not just an axis-aligned rectangle, since it scales from the shape's own centroid rather than
     * assuming any particular orientation. A negative marginFraction shrinks the region instead -
     * used by verifyScreenEdges to probe just inside the traced boundary, not only outside it. */
    private static ScreenRegionSpec expandRegion(ScreenRegionSpec r, double marginFraction) {
        if (marginFraction == 0) return r;
        double cx = (r.getTopLeft().getX() + r.getTopRight().getX() + r.getBottomRight().getX() + r.getBottomLeft().getX()) / 4.0;
        double cy = (r.getTopLeft().getY() + r.getTopRight().getY() + r.getBottomRight().getY() + r.getBottomLeft().getY()) / 4.0;
        double scale = 1.0 + marginFraction;
        ScreenRegionSpec out = new ScreenRegionSpec();
        out.setTopLeft(scaleCorner(r.getTopLeft(), cx, cy, scale));
        out.setTopRight(scaleCorner(r.getTopRight(), cx, cy, scale));
        out.setBottomRight(scaleCorner(r.getBottomRight(), cx, cy, scale));
        out.setBottomLeft(scaleCorner(r.getBottomLeft(), cx, cy, scale));
        return out;
    }

    private static ScreenRegionSpec.Corner scaleCorner(ScreenRegionSpec.Corner c, double cx, double cy, double scale) {
        return new ScreenRegionSpec.Corner(cx + (c.getX() - cx) * scale, cy + (c.getY() - cy) * scale);
    }

    // ---- Whole-video rotation (0/90/180/270) - see JobRequest.sourceRotation's own doc for why
    // region coordinates are always given relative to the ORIGINAL, un-rotated frame (the wizard
    // preview never visually rotates) and are transformed here to match, rather than asking the
    // frontend to pre-rotate anything. ----

    private static int normalizeRotation(int deg) {
        int r = deg % 360;
        if (r < 0) r += 360;
        return (r == 90 || r == 180 || r == 270) ? r : 0;
    }

    /** ffmpeg fragment for rotating a WHOLE frame by rotationDeg clockwise, no trailing comma -
     * empty string for 0° (never "transpose=0", which is itself a real - and different -
     * transform, not a no-op). transpose=1/2 alone (no flip) are ffmpeg's plain 90°CW/90°CCW; 180°
     * is done as hflip+vflip rather than a double transpose, cheaper and clearer for what it is. */
    private static String rotationFilterFragment(int rotationDeg) {
        switch (normalizeRotation(rotationDeg)) {
            case 90: return "transpose=1";
            case 180: return "hflip,vflip";
            case 270: return "transpose=2";
            default: return "";
        }
    }

    /** Where a point at (x,y) in a w-by-h frame ends up after rotating the WHOLE frame by
     * rotationDeg clockwise. Matches ffmpeg's own transpose/hflip+vflip semantics exactly - derived
     * and cross-checked by hand against concrete corner cases (a frame's own 4 corners under each
     * rotation) before use, the same rigor already applied to the perspective filter's own corner
     * convention elsewhere in this file. */
    private static double[] rotatePoint(double x, double y, int rotationDeg, double w, double h) {
        switch (normalizeRotation(rotationDeg)) {
            case 90: return new double[]{h - y, x};
            case 180: return new double[]{w - x, h - y};
            case 270: return new double[]{y, w - x};
            default: return new double[]{x, y};
        }
    }

    /**
     * Rotates a screen region's 4 corners for a whole-frame rotation. Deliberately NOT just
     * "rotatePoint each named corner and keep its name" - that preserves which STORED point is
     * called topLeft but not what topLeft actually MEANS to the perspective filter downstream (the
     * destination for the overlay's own top-left pixel), so after a 90°/270° turn the overlay would
     * come out looking rotated/twisted within its own (correctly relocated) screen. What must be
     * preserved instead is which corner is geometrically top-left AFTER the turn - equivalent to a
     * cyclic relabeling of the 4 corners (90°CW: the old bottom-left becomes the new top-left, etc,
     * exactly like turning a photo) alongside rotating each point's own coordinates. Verified by
     * hand on a concrete non-square rectangle for all three rotations before relying on it.
     */
    /** Rotates a round no-light zone for a whole-frame rotation: the centre moves like any point and,
     * for 90/270, the axis-aligned ellipse stays axis-aligned with its two radii swapped. */
    private static EllipseZoneSpec rotateEllipse(EllipseZoneSpec e, int rotationDeg, double w, double h) {
        int rot = normalizeRotation(rotationDeg);
        if (rot == 0) return e;
        double[] c = rotatePoint(e.getCx(), e.getCy(), rot, w, h);
        boolean swap = rot == 90 || rot == 270;
        return new EllipseZoneSpec(c[0], c[1], swap ? e.getRy() : e.getRx(), swap ? e.getRx() : e.getRy());
    }

    private static ScreenRegionSpec rotateRegion(ScreenRegionSpec r, int rotationDeg, double w, double h) {
        int rot = normalizeRotation(rotationDeg);
        if (rot == 0) return r;
        ScreenRegionSpec.Corner tl = r.getTopLeft(), tr = r.getTopRight(), br = r.getBottomRight(), bl = r.getBottomLeft();
        ScreenRegionSpec out = new ScreenRegionSpec();
        switch (rot) {
            case 90:
                out.setTopLeft(rotateCorner(bl, rot, w, h));
                out.setTopRight(rotateCorner(tl, rot, w, h));
                out.setBottomRight(rotateCorner(tr, rot, w, h));
                out.setBottomLeft(rotateCorner(br, rot, w, h));
                break;
            case 180:
                out.setTopLeft(rotateCorner(br, rot, w, h));
                out.setTopRight(rotateCorner(bl, rot, w, h));
                out.setBottomRight(rotateCorner(tl, rot, w, h));
                out.setBottomLeft(rotateCorner(tr, rot, w, h));
                break;
            default: // 270
                out.setTopLeft(rotateCorner(tr, rot, w, h));
                out.setTopRight(rotateCorner(br, rot, w, h));
                out.setBottomRight(rotateCorner(bl, rot, w, h));
                out.setBottomLeft(rotateCorner(tl, rot, w, h));
                break;
        }
        return out;
    }

    private static ScreenRegionSpec.Corner rotateCorner(ScreenRegionSpec.Corner c, int rotationDeg, double w, double h) {
        double[] p = rotatePoint(c.getX(), c.getY(), rotationDeg, w, h);
        return new ScreenRegionSpec.Corner(p[0], p[1]);
    }

    /** Rotates an axis-aligned x/y/width/height rect (blur zones, overlay elements) for a
     * whole-frame rotation - unlike a screen's 4 independently-named corners, there's no role-
     * relabeling question here: rotate the 4 implied corners and take the new bounding box, which
     * is inherently correct for a shape that's only ever described by its own extent. */
    private static int[] rotateRect(int x, int y, int width, int height, int rotationDeg, double w, double h) {
        int rot = normalizeRotation(rotationDeg);
        if (rot == 0) return new int[]{x, y, width, height};
        double[] p1 = rotatePoint(x, y, rot, w, h);
        double[] p2 = rotatePoint(x + width, y, rot, w, h);
        double[] p3 = rotatePoint(x + width, y + height, rot, w, h);
        double[] p4 = rotatePoint(x, y + height, rot, w, h);
        double minX = Math.min(Math.min(p1[0], p2[0]), Math.min(p3[0], p4[0]));
        double maxX = Math.max(Math.max(p1[0], p2[0]), Math.max(p3[0], p4[0]));
        double minY = Math.min(Math.min(p1[1], p2[1]), Math.min(p3[1], p4[1]));
        double maxY = Math.max(Math.max(p1[1], p2[1]), Math.max(p3[1], p4[1]));
        return new int[]{(int) Math.round(minX), (int) Math.round(minY),
                (int) Math.round(maxX - minX), (int) Math.round(maxY - minY)};
    }

    /** Public so JobProcessor can rotate the SAME regions it passes into compose() before also
     * passing them to verifyScreenEdges - that check reads the real (already-rotated) composed
     * video, so it needs coordinates in that same rotated space, not the raw ones. */
    public Map<String, ScreenRegionSpec> rotateScreenRegions(Map<String, ScreenRegionSpec> regions,
                                                               int sourceRotation, int frameWidth, int frameHeight) {
        if (normalizeRotation(sourceRotation) == 0 || regions == null) return regions;
        Map<String, ScreenRegionSpec> out = new LinkedHashMap<>();
        for (Map.Entry<String, ScreenRegionSpec> e : regions.entrySet()) {
            out.put(e.getKey(), rotateRegion(e.getValue(), sourceRotation, frameWidth, frameHeight));
        }
        return out;
    }

    /**
     * Best-effort post-compose check for the single most obvious "this is a green-screen composite"
     * tell there is: any remaining trace of the key color visible right at a screen's boundary in
     * the delivered video. Verified empirically (real footage, not synthetic) that this defect sits
     * at a roughly constant PIXEL distance from each edge's own true line - not a percentage of the
     * region's size, and not the same distance or even the same side (inward vs outward) on every
     * edge of the same screen. A real screen's traced edge is rarely a hard cut: lens/compression
     * blur or a recessed bezel's own shadow typically produces a several-to-twenty-pixel transitional
     * band that can itself contain a dark, desaturated-but-still-green-hued strip chromakey's
     * similarity threshold doesn't remove (what despill exists to clean up) - and on the one real
     * clip this was diagnosed against, that band sat 12-23px INSIDE the traced line on every one of
     * its 4 edges, not outside it, which an earlier version of this check (built around growing the
     * whole region outward from its center) could never have found regardless of range, since
     * outward is the wrong direction for an edge whose true line sits inside the traced one. So each
     * of the 4 edges is walked independently along its OWN outward normal, sweeping a wide range of
     * perpendicular pixel offsets on both sides of the traced line, and the worst single edge decides
     * the verdict. Never throws - a failed check is silently skipped (logged only), since a
     * diagnostic must not be able to fail a job that otherwise composed successfully.
     *
     * @param litFrames colour -> that screen's ambient-light TV frame (delivered-frame coords), for
     *                  screens with the light on. Sample points OUTSIDE it are skipped: the light may be
     *                  drawn there, and light from a green scene (a football pitch) passes the key-color
     *                  test - confirmed on a real render, where it alone raised an edge from 20% to 55%
     *                  "key color" and fired a false warning. The green screen itself sits inside the
     *                  TV's border, where that frame is placed, so leftover key color is still caught.
     */
    public List<String> verifyScreenEdges(Path composedVideo, List<String> activeColors,
                                           Map<String, ScreenRegionSpec> screenRegions,
                                           Map<String, ChromaKeySpec> chromaKeyTuning,
                                           Map<String, ScreenRegionSpec> litFrames, double videoDuration) {
        List<String> warnings = new ArrayList<>();
        if (activeColors == null || activeColors.isEmpty()) return warnings;
        Path frame = null;
        try {
            frame = Files.createTempFile("rstms-edgecheck-", ".png");
            ffmpeg.ffmpeg(List.of("-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, videoDuration / 2)),
                    "-i", composedVideo.toAbsolutePath().toString(),
                    "-frames:v", "1", "-update", "1", frame.toAbsolutePath().toString()),
                    "extracting a frame for screen-edge verification");
            BufferedImage img = ImageIO.read(frame.toFile());
            if (img == null) return warnings;

            for (String color : activeColors) {
                ScreenRegionSpec region = screenRegions.get(color);
                if (region == null) continue;
                ChromaKeySpec tuning = chromaKeyTuning != null && chromaKeyTuning.get(color) != null
                        ? chromaKeyTuning.get(color) : new ChromaKeySpec();
                String hex = tuning.getColor() != null ? tuning.getColor() : SCREEN_DEFAULT_HEX.get(color);
                int[] rgb = parseHexColor(hex);
                float[] targetHsb = Color.RGBtoHSB(rgb[0], rgb[1], rgb[2], null);
                double cx = (region.getTopLeft().getX() + region.getTopRight().getX()
                        + region.getBottomRight().getX() + region.getBottomLeft().getX()) / 4.0;
                double cy = (region.getTopLeft().getY() + region.getTopRight().getY()
                        + region.getBottomRight().getY() + region.getBottomLeft().getY()) / 4.0;
                ScreenRegionSpec.Corner[] pts = {region.getTopLeft(), region.getTopRight(),
                        region.getBottomRight(), region.getBottomLeft()};
                ScreenRegionSpec litFrame = litFrames != null ? litFrames.get(color) : null;
                LightingMask.Shape unlitHole = litFrame != null ? LightingMask.shape(litFrame, 1.1) : null;

                double worstFraction = 0;
                for (int e = 0; e < 4; e++) {
                    ScreenRegionSpec.Corner a = pts[e], b = pts[(e + 1) % 4];
                    for (int offsetPx = -25; offsetPx <= 40; offsetPx += 2) {
                        double f = fractionOfEdgeMatchingKeyColor(img, a, b, offsetPx, cx, cy, targetHsb, unlitHole);
                        if (f > worstFraction) worstFraction = f;
                    }
                }
                if (worstFraction > 0.5) {
                    warnings.add(String.format(Locale.ROOT,
                            "L'écran %s montre encore une trace de la couleur de détourage près de son bord "
                            + "(jusqu'à %.0f%% le long d'un côté) - la vidéo risque de trahir le montage. Essayez "
                            + "d'augmenter l'anti-débordement (despill) pour cet écran ; si ça ne suffit pas, "
                            + "agrandissez légèrement la zone tracée ou sa marge de sécurité, puis relancez la "
                            + "composition.",
                            color, worstFraction * 100));
                }
            }
        } catch (Exception e) {
            log.warn("Screen edge verification could not run (job still succeeds - this is a diagnostic only): {}", e.getMessage());
        } finally {
            if (frame != null) {
                try { Files.deleteIfExists(frame); } catch (Exception ignored) { }
            }
        }
        return warnings;
    }

    /** Fraction of ~40 points sampled along the line from a to b, each offset by offsetPx pixels
     * perpendicular to that line (positive = outward, away from the region's center (cx,cy);
     * negative = inward), whose pixel in img is a close hue match to targetHsb at high saturation
     * and low-but-nonzero brightness - i.e. still looks like key color, whether a bright untouched
     * fill or a dark partially-keyed transitional pixel, rather than real background or overlay
     * content. When unlitHole is given, points outside it (where the ambient light may be drawn) are
     * skipped, and a line with fewer than 10 remaining points is not judged at all. */
    private static double fractionOfEdgeMatchingKeyColor(BufferedImage img, ScreenRegionSpec.Corner a,
                                                           ScreenRegionSpec.Corner b, double offsetPx,
                                                           double cx, double cy, float[] targetHsb,
                                                           LightingMask.Shape unlitHole) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return 0;
        double nx = -dy / len, ny = dx / len;
        double midX = (a.getX() + b.getX()) / 2, midY = (a.getY() + b.getY()) / 2;
        if (nx * (midX - cx) + ny * (midY - cy) < 0) { nx = -nx; ny = -ny; }

        int samples = 40;
        int matched = 0, total = 0;
        for (int s = 0; s < samples; s++) {
            double t = (s + 0.5) / samples;
            int x = (int) Math.round(a.getX() + dx * t + nx * offsetPx);
            int y = (int) Math.round(a.getY() + dy * t + ny * offsetPx);
            if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) continue;
            if (unlitHole != null && LightingMask.distanceOutside(unlitHole, x + 0.5, y + 0.5) > 0) continue;
            int px = img.getRGB(x, y);
            float[] hsb = Color.RGBtoHSB((px >> 16) & 0xFF, (px >> 8) & 0xFF, px & 0xFF, null);
            double hueDistDeg = Math.abs(hsb[0] - targetHsb[0]) * 360.0;
            if (hueDistDeg > 180) hueDistDeg = 360 - hueDistDeg;
            total++;
            // The value/brightness floor is deliberately low: real footage's transitional band at a
            // screen's true edge (bezel shadow, lens/compression blur) measured as low as val=0.11
            // while still being unmistakably green - hue 118-126°, saturation 0.78-1.0. Saturation
            // stays a high bar precisely because that's what still separates a genuinely color-tinted
            // dark pixel from an ordinary neutral shadow at similar brightness.
            if (hueDistDeg < 18 && hsb[1] > 0.5 && hsb[2] > 0.06) matched++;
        }
        return total < (unlitHole != null ? 10 : 1) ? 0 : (double) matched / total;
    }

    private static int[] parseHexColor(String hex) {
        String h = hex.replaceFirst("^#", "").replaceFirst("^0[xX]", "");
        int v = Integer.parseInt(h, 16);
        return new int[]{(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
    }
}

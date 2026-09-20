package com.rstms.service;

import com.rstms.model.BlurRegionSpec;
import com.rstms.model.GreenScreenSpec;
import com.rstms.model.ScreenRegionSpec;
import com.rstms.video.FfmpegRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Muxes the new audio onto the source video, optionally compositing up to three overlay videos
 * onto colored chroma-key screens (green/blue/red) in the base video, blurring one or more regions
 * for a time range, and/or burning in captions. The video is never re-cut - these only change
 * pixels within the existing timeline. When none of them are requested, the video stream is
 * stream-copied (no re-encode, no quality loss); otherwise a filter_complex chain is built and
 * it's re-encoded once for every effect together.
 */
@Component
public class VideoComposer {

    /** Fixed processing order: green is the mandatory/primary screen, blue and red are optional
     * additional ones on the same base video (e.g. a second small screen). */
    private static final List<String> SCREEN_ORDER = List.of("green", "blue", "red");
    private static final Map<String, String> SCREEN_DEFAULT_HEX = Map.of(
            "green", "0x00FF00", "blue", "0x0000FF", "red", "0xFF0000");

    private final FfmpegRunner ffmpeg;

    public VideoComposer(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
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
     * @param greenScreenTuning Optional similarity/blend/color override for the green screen
     *                          specifically - blue/red use fixed defaults (no UI exposes tuning
     *                          for them yet).
     */
    public Path compose(Path sourceVideo, Path masterAudio, Path captionsSrt, List<BlurRegionSpec> blurRegions,
                         Map<String, Path> overlayVideos, Map<String, ScreenRegionSpec> screenRegions,
                         GreenScreenSpec greenScreenTuning, double videoDuration, Path targetMp4) {
        boolean hasBlur = blurRegions != null && !blurRegions.isEmpty();
        List<String> activeColors = new ArrayList<>();
        if (overlayVideos != null) {
            for (String color : SCREEN_ORDER) {
                if (overlayVideos.containsKey(color)) activeColors.add(color);
            }
        }
        boolean hasOverlay = !activeColors.isEmpty();
        boolean needsVideoFilter = captionsSrt != null || hasBlur || hasOverlay;

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

        if (needsVideoFilter) {
            args.add("-filter_complex");
            args.add(buildVideoFilter(captionsSrt, hasBlur ? blurRegions : List.of(),
                    activeColors, inputIndexByColor, screenRegions, greenScreenTuning, videoDuration));
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
        if (hasBlur) what += " with " + blurRegions.size() + " blur region(s)";
        if (captionsSrt != null) what += " and burned captions";
        ffmpeg.ffmpeg(args, what);
        return targetMp4;
    }

    private static String buildVideoFilter(Path captionsSrt, List<BlurRegionSpec> blurRegions,
                                            List<String> activeColors, Map<String, Integer> inputIndexByColor,
                                            Map<String, ScreenRegionSpec> screenRegions,
                                            GreenScreenSpec greenScreenTuning, double videoDuration) {
        StringBuilder filter = new StringBuilder();
        String cur = "0:v";

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
            // footage, so chromakey naturally leaves them alone; (4) drop the warped overlay in
            // behind that keyed crop; (5) paste the result back at the bounding box's origin. Step 3
            // keys the *running composite*, not a fresh base copy, so an earlier pass's content
            // survives - untouched pixels there are still identical to the original base.
            filter.append(String.format(Locale.ROOT, "[0:v]trim=0:%.3f,setpts=PTS-STARTPTS[gsbase];", videoDuration));
            cur = "gsbase";
            int step = 0;
            for (String color : activeColors) {
                int idx = inputIndexByColor.get(color);
                ScreenRegionSpec region = screenRegions.get(color);
                int[] bbox = boundingBox(region);
                int bx = bbox[0], by = bbox[1], bw = bbox[2], bh = bbox[3];
                boolean isGreen = "green".equals(color);
                String hex = isGreen && greenScreenTuning.getColor() != null
                        ? greenScreenTuning.getColor() : SCREEN_DEFAULT_HEX.get(color);
                double similarity = isGreen ? greenScreenTuning.getSimilarity() : 0.18;
                double blend = isGreen ? greenScreenTuning.getBlend() : 0.06;

                String ovlFitted = "gsovlfit" + step;
                String ovlWarped = "gsovlwarp" + step;
                String regionCrop = "gsregioncrop" + step;
                String regionKeyed = "gsregionkeyed" + step;
                String regionComp = "gsregioncomp" + step;
                String next = "gscomposite" + step;

                // pad's target uses max(iw,w)/max(ih,h) rather than the bare w/h: scale's own
                // aspect-preserving rounding can overshoot the nominal target by a pixel (a known
                // ffmpeg quirk), and padding to an exact size smaller than what scale just produced
                // fails outright ("Padded dimensions cannot be smaller than input dimensions").
                filter.append('[').append(idx).append(":v]")
                      .append(String.format(Locale.ROOT,
                              "trim=0:%.3f,setpts=PTS-STARTPTS,scale=%d:%d:force_original_aspect_ratio=decrease,"
                              + "pad=max(iw\\,%d):max(ih\\,%d):(ow-iw)/2:(oh-ih)/2:black",
                              videoDuration, bw, bh, bw, bh))
                      .append('[').append(ovlFitted).append("];");

                filter.append('[').append(ovlFitted).append(']')
                      // sense=destination is essential here and NOT the filter's default (which is
                      // "source" - an inverse mapping that samples the source at these points rather
                      // than sending the source's own corners to them). Verified empirically: with
                      // the default, corners warp in the mirror-opposite direction from intended.
                      .append(String.format(Locale.ROOT,
                              "perspective=x0=%.2f:y0=%.2f:x1=%.2f:y1=%.2f:x2=%.2f:y2=%.2f:x3=%.2f:y3=%.2f:sense=destination",
                              region.getTopLeft().getX() - bx, region.getTopLeft().getY() - by,
                              region.getTopRight().getX() - bx, region.getTopRight().getY() - by,
                              region.getBottomLeft().getX() - bx, region.getBottomLeft().getY() - by,
                              region.getBottomRight().getX() - bx, region.getBottomRight().getY() - by))
                      .append('[').append(ovlWarped).append("];");

                filter.append('[').append(cur).append(']')
                      .append(String.format(Locale.ROOT, "crop=%d:%d:%d:%d", bw, bh, bx, by))
                      .append('[').append(regionCrop).append("];");
                filter.append('[').append(regionCrop).append(']')
                      .append(String.format(Locale.ROOT, "chromakey=%s:%.3f:%.3f", hex, similarity, blend))
                      .append('[').append(regionKeyed).append("];");
                filter.append('[').append(ovlWarped).append("][").append(regionKeyed).append(']')
                      .append("overlay=shortest=1[").append(regionComp).append("];");

                filter.append('[').append(cur).append("][").append(regionComp).append(']')
                      .append(String.format(Locale.ROOT, "overlay=%d:%d", bx, by))
                      .append('[').append(next).append("];");
                cur = next;
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
                  .append("[vout]");
        } else {
            filter.append('[').append(cur).append(']').append("null[vout]");
        }

        return filter.toString();
    }

    /** ffmpeg's subtitles filter parses its path with its own mini-syntax - colons and backslashes need escaping. */
    private static String escapeForFilter(String path) {
        return path.replace("\\", "/").replace(":", "\\:");
    }

    /** The smallest axis-aligned pixel box fully containing all four (possibly non-rectangular)
     * corners - floor/ceil rather than plain rounding so the box never clips a fractional pixel. */
    private static int[] boundingBox(ScreenRegionSpec r) {
        double minX = Math.min(Math.min(r.getTopLeft().getX(), r.getTopRight().getX()),
                Math.min(r.getBottomLeft().getX(), r.getBottomRight().getX()));
        double minY = Math.min(Math.min(r.getTopLeft().getY(), r.getTopRight().getY()),
                Math.min(r.getBottomLeft().getY(), r.getBottomRight().getY()));
        double maxX = Math.max(Math.max(r.getTopLeft().getX(), r.getTopRight().getX()),
                Math.max(r.getBottomLeft().getX(), r.getBottomRight().getX()));
        double maxY = Math.max(Math.max(r.getTopLeft().getY(), r.getTopRight().getY()),
                Math.max(r.getBottomLeft().getY(), r.getBottomRight().getY()));
        int bx = (int) Math.floor(minX);
        int by = (int) Math.floor(minY);
        int bw = (int) Math.ceil(maxX) - bx;
        int bh = (int) Math.ceil(maxY) - by;
        // libx264's default yuv420p output requires even width/height (2x2 chroma subsampling) -
        // an odd bounding box dimension doesn't fail cleanly with a clear message, it surfaces deep
        // in the scale+pad step as a cryptic "Padded dimensions cannot be smaller than input
        // dimensions" once the encoder starts negotiating formats. Round down (never up) so the box
        // never grows past whatever it was already computed to fit within.
        if (bw % 2 != 0) bw--;
        if (bh % 2 != 0) bh--;
        return new int[]{bx, by, bw, bh};
    }
}

package com.rstms.service;

import com.rstms.model.SegmentSpec;
import com.rstms.video.FfmpegRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the final audio track in one ffmpeg call: the original audio plays everywhere except
 * under a narrated segment (fully ducked and replaced by fitted English narration), one-shot
 * sound effects are mixed in on top at their timestamps, and zero or more full-length beds (a
 * background music track, and/or - for a green-screen job - the overlay video's own audio) loop
 * under everything for the whole video. If the source video has no audio track at all (e.g. a
 * silent green-screen room template), silence fills that base layer instead of erroring out. The
 * source's own audio (when present) is itself looped and trimmed to videoDuration too, not just
 * assumed to already match it - for a green-screen job videoDuration comes from the (normally
 * longer) overlay clip, so the base's own audio needs to repeat to cover the full result, the same
 * way its video track does. Loudness normalization is applied last so the result lands at a
 * consistent perceived volume regardless of how many layers went in.
 */
@Component
public class AudioMuxer {

    private final FfmpegRunner ffmpeg;

    public AudioMuxer(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public record PlacedSegment(SegmentSpec spec, Path wav) {}
    public record PlacedEffect(double start, Path file, double volume) {}
    public record MusicBed(Path file, double volume) {}

    /**
     * @param beds Zero or more full-length audio layers looped and trimmed to videoDuration - a
     *             background music track and/or (for a green-screen job) the overlay video's own
     *             audio, mixed in alongside the base video's audio exactly the same way.
     */
    public Path buildMasterAudio(Path sourceVideo, boolean sourceHasAudio, double videoDuration,
                                  List<PlacedSegment> segments, List<PlacedEffect> effects, List<MusicBed> beds,
                                  boolean loudnessNormalize, Path targetWav) {
        List<String> args = new ArrayList<>();
        // Looped like the beds below: harmless when the source's own audio already covers
        // videoDuration in one pass (the normal case, where the trim below cuts at that same
        // point anyway), and correct for a green-screen job where videoDuration comes from the
        // (usually longer) overlay clip instead - the base's own audio then loops to fill it.
        args.add("-stream_loop");
        args.add("-1");
        args.add("-i");
        args.add(sourceVideo.toAbsolutePath().toString());

        for (PlacedSegment seg : segments) {
            args.add("-i");
            args.add(seg.wav().toAbsolutePath().toString());
        }
        for (PlacedEffect fx : effects) {
            args.add("-i");
            args.add(fx.file().toAbsolutePath().toString());
        }
        List<Integer> bedInputIndexes = new ArrayList<>();
        for (MusicBed bed : beds) {
            // -stream_loop applies to the *next* -i only: loop the bed indefinitely, then atrim
            // below cuts it down to the video's exact length.
            args.add("-stream_loop");
            args.add("-1");
            args.add("-i");
            args.add(bed.file().toAbsolutePath().toString());
            bedInputIndexes.add(1 + segments.size() + effects.size() + bedInputIndexes.size());
        }

        StringBuilder filter = new StringBuilder();

        if (!sourceHasAudio) {
            // No original audio track at all (e.g. a silent green-screen room template) - nothing
            // to preserve or duck, so the base layer is just silence; narration/effects/beds still
            // mix in on top of it exactly as usual.
            filter.append(String.format(Locale.ROOT, "anullsrc=r=44100:cl=stereo:d=%.3f[dorig];", videoDuration));
        } else if (segments.isEmpty()) {
            filter.append(String.format(Locale.ROOT, "[0:a]atrim=0:%.3f,asetpts=PTS-STARTPTS[dorig];", videoDuration));
        } else {
            StringBuilder duckExpr = new StringBuilder();
            for (PlacedSegment seg : segments) {
                if (duckExpr.length() > 0) duckExpr.append("+");
                duckExpr.append(String.format(Locale.ROOT, "between(t,%.3f,%.3f)",
                        seg.spec().getStart(), seg.spec().getEnd()));
            }
            filter.append(String.format(Locale.ROOT,
                    "[0:a]atrim=0:%.3f,asetpts=PTS-STARTPTS,volume=enable='%s':volume=0[dorig];", videoDuration, duckExpr));
        }

        StringBuilder mixInputs = new StringBuilder("[dorig]");
        int inputIdx = 1;
        for (PlacedSegment seg : segments) {
            long delayMs = Math.round(seg.spec().getStart() * 1000.0);
            String label = "narr" + inputIdx;
            filter.append(String.format(Locale.ROOT, "[%d:a]adelay=%d|%d[%s];", inputIdx, delayMs, delayMs, label));
            mixInputs.append('[').append(label).append(']');
            inputIdx++;
        }
        for (PlacedEffect fx : effects) {
            long delayMs = Math.round(fx.start() * 1000.0);
            String label = "fx" + inputIdx;
            filter.append(String.format(Locale.ROOT, "[%d:a]volume=%.3f,adelay=%d|%d[%s];",
                    inputIdx, fx.volume(), delayMs, delayMs, label));
            mixInputs.append('[').append(label).append(']');
            inputIdx++;
        }

        int totalMixInputs = 1 + segments.size() + effects.size();
        for (int i = 0; i < beds.size(); i++) {
            MusicBed bed = beds.get(i);
            String label = "bed" + i;
            filter.append(String.format(Locale.ROOT,
                    "[%d:a]atrim=0:%.3f,volume=%.3f[%s];", bedInputIndexes.get(i), videoDuration, bed.volume(), label));
            mixInputs.append('[').append(label).append(']');
            totalMixInputs++;
        }

        // normalize=0: layers were already given explicit relative volumes (ducking to 0, a music
        // bed deliberately kept low), so amix's default loudness normalization would just quiet
        // everything down for no reason - loudnorm below handles final level, not amix.
        filter.append(mixInputs)
              .append("amix=inputs=").append(totalMixInputs)
              .append(":duration=first:dropout_transition=0:normalize=0");

        if (loudnessNormalize) {
            filter.append("[premix];[premix]loudnorm=I=-14:TP=-1.5:LRA=11[aout]");
        } else {
            filter.append("[aout]");
        }

        args.add("-filter_complex");
        args.add(filter.toString());
        args.add("-map");
        args.add("[aout]");
        args.add("-ac");
        args.add("2");
        args.add(targetWav.toAbsolutePath().toString());

        ffmpeg.ffmpeg(args, "mixing narration, effects and music into master audio");
        return targetWav;
    }
}

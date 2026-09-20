package com.rstms.service;

import com.rstms.config.AppProperties;
import com.rstms.model.SegmentSpec;
import com.rstms.tts.TtsService;
import com.rstms.video.FfmpegRunner;
import com.rstms.video.MediaProbe;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Fits English narration into a video segment whose start/end are already locked - the video is
 * never re-cut. Piper's speaking-rate control (see PiperTtsService) does the work: synthesize at
 * natural pace, measure how far off the target duration it is, then re-synthesize at a rate that
 * closes the gap. If the gap is too large to close naturally, we say so instead of guessing.
 */
@Component
public class SegmentFitEngine {

    public record FitResult(Path wav, double targetSeconds, double naturalSeconds,
                             double appliedRate, double finalSeconds, String warning) {}

    private final TtsService tts;
    private final FfmpegRunner ffmpeg;
    private final MediaProbe probe;
    private final AppProperties props;

    public SegmentFitEngine(TtsService tts, FfmpegRunner ffmpeg, MediaProbe probe, AppProperties props) {
        this.tts = tts;
        this.ffmpeg = ffmpeg;
        this.probe = probe;
        this.props = props;
    }

    public FitResult fit(SegmentSpec segment, String defaultVoice, Path workDir, int index) {
        double target = segment.getEnd() - segment.getStart();
        String voice = segment.getVoice() != null ? segment.getVoice() : defaultVoice;

        Path naturalWav = workDir.resolve(String.format("segment_%03d_natural.wav", index));
        tts.synthesize(segment.getText(), voice, 1.0, naturalWav);
        double natural = probe.durationSeconds(naturalWav);

        double minRate = props.getFit().getMinRate();
        double maxRate = props.getFit().getMaxRate();
        double desiredRate = natural / target;
        double appliedRate = Math.max(minRate, Math.min(maxRate, desiredRate));

        Path finalWav;
        if (Math.abs(appliedRate - 1.0) < 0.01) {
            finalWav = naturalWav;
        } else {
            finalWav = workDir.resolve(String.format("segment_%03d.wav", index));
            tts.synthesize(segment.getText(), voice, appliedRate, finalWav);
        }

        double actual = probe.durationSeconds(finalWav);
        String warning = null;

        if (desiredRate < minRate || desiredRate > maxRate) {
            warning = String.format(Locale.ROOT,
                    "segment %d (%.1fs-%.1fs) : le texte demande une vitesse de %.2fx pour tenir naturellement, limitée à %.2fx",
                    index, segment.getStart(), segment.getEnd(), desiredRate, appliedRate);
        }

        // Safety net regardless of how close Piper's length-scale landed: never let a segment's
        // audio run past its slot, since segments are placed on the timeline by absolute offset
        // and an overrun would bleed into the next one.
        if (actual > target + 0.05) {
            Path trimmed = workDir.resolve(String.format("segment_%03d_trimmed.wav", index));
            ffmpeg.ffmpeg(List.of(
                    "-i", finalWav.toAbsolutePath().toString(),
                    "-t", String.format(Locale.ROOT, "%.3f", target),
                    "-c", "copy",
                    trimmed.toAbsolutePath().toString()
            ), "découpage du segment " + index + " pour tenir dans son créneau");
            finalWav = trimmed;
            String truncationNote = String.format(Locale.ROOT,
                    "segment %d (%.1fs-%.1fs) : l'audio dépassait de %.2fs son créneau même à %.2fx et a été tronqué - raccourcissez le texte",
                    index, segment.getStart(), segment.getEnd(), actual - target, appliedRate);
            warning = warning == null ? truncationNote : warning + "; " + truncationNote;
            actual = target;
        }

        return new FitResult(finalWav, target, natural, appliedRate, actual, warning);
    }
}

package com.rstms.video;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Duration lookups, memoised per file path. */
@Component
public class MediaProbe {

    private final FfmpegRunner runner;
    private final Map<String, Double> durationCache = new HashMap<>();

    public MediaProbe(FfmpegRunner runner) { this.runner = runner; }

    public double durationSeconds(Path file) {
        String key = file.toAbsolutePath().toString();
        Double cached = durationCache.get(key);
        if (cached != null) return cached;

        String out = runner.ffprobe(List.of(
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                key));
        double d;
        try {
            d = Double.parseDouble(out.trim());
        } catch (NumberFormatException e) {
            throw new FfmpegRunner.FfmpegException("Could not read duration of " + file + " (ffprobe said: '" + out + "')");
        }
        if (d <= 0 || Double.isNaN(d)) {
            throw new FfmpegRunner.FfmpegException("File reports a non-positive duration, is it valid media? " + file);
        }
        durationCache.put(key, d);
        return d;
    }

    /** Some source videos (e.g. a silent green-screen room template) have no audio track at all. */
    public boolean hasAudioStream(Path file) {
        String out = runner.ffprobe(List.of(
                "-select_streams", "a",
                "-show_entries", "stream=index",
                "-of", "csv=p=0",
                file.toAbsolutePath().toString()));
        return !out.isBlank();
    }

    /** {width, height} of the first video stream - used to keep a computed crop/composite region
     * from stepping outside the actual frame. */
    public int[] videoSize(Path file) {
        String out = runner.ffprobe(List.of(
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height",
                "-of", "csv=p=0:s=x",
                file.toAbsolutePath().toString()));
        String[] parts = out.trim().split("x");
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (Exception e) {
            throw new FfmpegRunner.FfmpegException("Could not read the video size of " + file + " (ffprobe said: '" + out + "')");
        }
    }
}

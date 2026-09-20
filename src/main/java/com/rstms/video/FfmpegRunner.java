package com.rstms.video;

import com.rstms.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Thin, honest wrapper around the ffmpeg / ffprobe binaries. */
@Component
public class FfmpegRunner {

    private static final Logger log = LoggerFactory.getLogger(FfmpegRunner.class);

    private final AppProperties props;

    public FfmpegRunner(AppProperties props) { this.props = props; }

    public void verifyBinaries() {
        for (String bin : List.of(props.getPipeline().getFfmpegBinary(), props.getPipeline().getFfprobeBinary())) {
            try {
                Result r = exec(List.of(bin, "-version"), 20);
                if (r.exitCode() != 0) throw new IllegalStateException("exit " + r.exitCode());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "'" + bin + "' is not runnable. Install FFmpeg and make sure it is on your PATH, "
                        + "or set app.pipeline.ffmpeg-binary / ffprobe-binary to absolute paths. Cause: " + e.getMessage());
            }
        }
    }

    /** Runs ffmpeg with -y and the configured loglevel already applied. */
    public void ffmpeg(List<String> args, String what) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getPipeline().getFfmpegBinary());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add(props.getPipeline().getFfmpegLoglevel());
        cmd.add("-y");
        cmd.addAll(args);

        log.debug("ffmpeg {}", String.join(" ", args));
        Result r = exec(cmd, 3600);
        if (r.exitCode() != 0) {
            throw new FfmpegException("FFmpeg failed while " + what + " (exit " + r.exitCode() + ").\n"
                    + "Command: " + String.join(" ", cmd) + "\n"
                    + "Output:\n" + r.output());
        }
    }

    public String ffprobe(List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(props.getPipeline().getFfprobeBinary());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.addAll(args);
        Result r = exec(cmd, 120);
        if (r.exitCode() != 0) {
            throw new FfmpegException("ffprobe failed (exit " + r.exitCode() + ").\n"
                    + "Command: " + String.join(" ", cmd) + "\nOutput:\n" + r.output());
        }
        return r.output().trim();
    }

    private Result exec(List<String> cmd, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() < 16000) sb.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new FfmpegException("Timed out after " + timeoutSeconds + "s: " + String.join(" ", cmd));
            }
            return new Result(p.exitValue(), sb.toString());
        } catch (FfmpegException e) {
            throw e;
        } catch (Exception e) {
            throw new FfmpegException("Could not run: " + String.join(" ", cmd) + " - " + e.getMessage(), e);
        }
    }

    public record Result(int exitCode, String output) {}

    public static class FfmpegException extends RuntimeException {
        public FfmpegException(String m) { super(m); }
        public FfmpegException(String m, Throwable c) { super(m, c); }
    }
}

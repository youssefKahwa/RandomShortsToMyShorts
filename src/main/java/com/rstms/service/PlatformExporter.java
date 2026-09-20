package com.rstms.service;

import com.rstms.config.AppProperties;
import com.rstms.video.FfmpegRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one platform-ready file from the composed master. v1 keeps this simple on purpose:
 * same edit for every platform, varying only duration cap / crf / audio bitrate per the confirmed
 * plan (no per-platform pacing/content differences - that's a v2 idea, not v1).
 */
@Component
public class PlatformExporter {

    private final FfmpegRunner ffmpeg;
    private final AppProperties props;

    public PlatformExporter(FfmpegRunner ffmpeg, AppProperties props) {
        this.ffmpeg = ffmpeg;
        this.props = props;
    }

    public Path export(Path masterMp4, String platform, Path targetMp4) {
        var video = props.getVideo();
        var platformCfg = props.getPlatforms().getOrDefault(platform, props.getPlatforms().get("default"));
        if (platformCfg == null) {
            throw new IllegalArgumentException("Unknown platform '" + platform + "' and no app.platforms.default configured");
        }

        List<String> args = new ArrayList<>();
        args.add("-i");
        args.add(masterMp4.toAbsolutePath().toString());

        if (platformCfg.getMaxDurationSeconds() > 0) {
            args.add("-t");
            args.add(String.valueOf(platformCfg.getMaxDurationSeconds()));
        }

        String scaleFilter = String.format(
                "scale=%d:%d:force_original_aspect_ratio=increase,crop=%d:%d",
                video.getWidth(), video.getHeight(), video.getWidth(), video.getHeight());
        args.add("-vf");
        args.add(scaleFilter);

        args.add("-c:v");
        args.add(video.getCodec());
        args.add("-preset");
        args.add(video.getPreset());
        args.add("-crf");
        args.add(String.valueOf(platformCfg.getCrf() > 0 ? platformCfg.getCrf() : video.getCrf()));
        args.add("-pix_fmt");
        args.add(video.getPixelFormat());
        args.add("-c:a");
        args.add("aac");
        args.add("-b:a");
        args.add(platformCfg.getAudioBitrate() != null ? platformCfg.getAudioBitrate() : video.getAudioBitrate());
        args.add("-movflags");
        args.add("+faststart");
        args.add(targetMp4.toAbsolutePath().toString());

        ffmpeg.ffmpeg(args, "rendering " + platform + " export");
        return targetMp4;
    }
}

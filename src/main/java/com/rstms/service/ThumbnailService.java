package com.rstms.service;

import com.rstms.video.FfmpegRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** A small poster frame per job so the job list isn't just a list of anonymous ids. Best-effort - never fatal. */
@Component
public class ThumbnailService {

    private final FfmpegRunner ffmpeg;

    public ThumbnailService(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    public void extract(Path sourceVideo, Path targetJpg) {
        ffmpeg.ffmpeg(List.of(
                "-i", sourceVideo.toAbsolutePath().toString(),
                "-ss", "00:00:00.5",
                "-frames:v", "1",
                "-vf", "scale=320:-2",
                targetJpg.toAbsolutePath().toString()
        ), "extracting job thumbnail");
    }
}

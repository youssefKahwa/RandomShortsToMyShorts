package com.rstms.config;

import com.rstms.video.FfmpegRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Fails fast and clearly if ffmpeg/ffprobe aren't runnable, instead of surfacing that mid-job. */
@Component
public class StartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private final FfmpegRunner ffmpeg;

    public StartupCheck(FfmpegRunner ffmpeg) {
        this.ffmpeg = ffmpeg;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ffmpeg.verifyBinaries();
            log.info("ffmpeg/ffprobe OK");
        } catch (Exception e) {
            log.error("Startup check failed: {}", e.getMessage());
        }
    }
}

package com.rstms.service;

import com.rstms.model.SegmentSpec;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Burned-in captions only, per the earlier decision: short-form viewers watch muted by default. */
@Component
public class CaptionWriter {

    public Path write(List<SegmentSpec> segments, Path targetSrt) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (SegmentSpec seg : segments) {
            sb.append(i++).append('\n');
            sb.append(timestamp(seg.getStart())).append(" --> ").append(timestamp(seg.getEnd())).append('\n');
            sb.append(seg.getText().trim()).append("\n\n");
        }
        try {
            Files.writeString(targetSrt, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write " + targetSrt, e);
        }
        return targetSrt;
    }

    private static String timestamp(double seconds) {
        int totalMs = (int) Math.round(seconds * 1000);
        int ms = totalMs % 1000;
        int totalSec = totalMs / 1000;
        int s = totalSec % 60;
        int totalMin = totalSec / 60;
        int m = totalMin % 60;
        int h = totalMin / 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}

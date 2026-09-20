package com.rstms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rstms.config.AppProperties;
import com.rstms.model.VoiceInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A small, hand-verified catalog of Piper voices (see voices-catalog.json) - gender, quality and,
 * critically, the *actual* dataset license from each voice's official MODEL_CARD. Piper's own
 * .onnx.json files carry none of that, and getting it wrong has real consequences (e.g. the
 * well-known "ryan" high-quality male voice is CC BY-NC-SA - non-commercial only).
 */
@Component
public class VoiceCatalogService {

    private final AppProperties props;
    private final List<VoiceInfo> catalog;

    public VoiceCatalogService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        try (InputStream in = new ClassPathResource("voices-catalog.json").getInputStream()) {
            this.catalog = List.of(mapper.readValue(in, VoiceInfo[].class));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load bundled voices-catalog.json", e);
        }
    }

    public List<VoiceInfo> all() {
        return catalog;
    }

    public Optional<VoiceInfo> find(String id) {
        return catalog.stream().filter(v -> v.getId().equals(id)).findFirst();
    }

    public boolean isDownloaded(VoiceInfo voice) {
        Path model = Path.of(props.getTts().getPiper().getVoicesDir(), voice.getId() + ".onnx");
        return Files.isRegularFile(model);
    }

    /** A pre-made sample shipped alongside the voice download, if any. */
    public Path staticSample(VoiceInfo voice) {
        if (voice.getSampleFile() == null) return null;
        String dir = props.getTts().getPiper().getSamplesDir();
        if (dir == null || dir.isBlank()) return null;
        Path p = Path.of(dir, voice.getSampleFile());
        return Files.isRegularFile(p) ? p : null;
    }
}

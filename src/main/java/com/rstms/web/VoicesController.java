package com.rstms.web;

import com.rstms.config.AppProperties;
import com.rstms.model.VoiceInfo;
import com.rstms.service.VoiceCatalogService;
import com.rstms.tts.TtsService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;

/** Lets you listen to each catalog voice before picking one, instead of guessing from a filename. */
@Controller
public class VoicesController {

    private final VoiceCatalogService catalog;
    private final TtsService tts;
    private final AppProperties props;

    public VoicesController(VoiceCatalogService catalog, TtsService tts, AppProperties props) {
        this.catalog = catalog;
        this.tts = tts;
        this.props = props;
    }

    @GetMapping("/voices")
    public String index(Model model) {
        model.addAttribute("voices", catalog.all());
        model.addAttribute("catalog", catalog);
        return "voices";
    }

    /** Serves the shipped sample if there is one, otherwise synthesizes and caches a short one on first request. */
    @GetMapping("/voices/{id}/sample")
    @ResponseBody
    public ResponseEntity<Resource> sample(@PathVariable String id) {
        VoiceInfo voice = catalog.find(id).orElseThrow();

        Path staticSample = catalog.staticSample(voice);
        if (staticSample != null) {
            return audio(staticSample);
        }
        if (!catalog.isDownloaded(voice)) {
            return ResponseEntity.notFound().build();
        }

        Path cacheDir = Path.of(props.getPaths().getData(), "voice-sample-cache");
        Path cached = cacheDir.resolve(id + ".wav");
        if (!Files.isRegularFile(cached)) {
            try {
                Files.createDirectories(cacheDir);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create " + cacheDir, e);
            }
            tts.synthesize("Hi, this is a quick preview of this voice, so you can hear how it sounds before choosing it.",
                    id, 1.0, cached);
        }
        return audio(cached);
    }

    private ResponseEntity<Resource> audio(Path file) {
        String contentType = file.toString().endsWith(".mp3") ? "audio/mpeg" : "audio/wav";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(new FileSystemResource(file));
    }
}

package com.rstms.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rstms.model.Job;
import com.rstms.model.JobRequest;
import com.rstms.model.JobStatus;
import com.rstms.service.AssetStore;
import com.rstms.service.JobQueueService;
import com.rstms.service.JobStore;
import com.rstms.service.JobValidator;
import com.rstms.service.ThumbnailService;
import com.rstms.service.VoiceCatalogService;
import com.rstms.video.MediaProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Controller
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobStore store;
    private final JobQueueService queue;
    private final ObjectMapper mapper;
    private final MediaProbe probe;
    private final JobValidator validator;
    private final ThumbnailService thumbnails;
    private final VoiceCatalogService voiceCatalog;
    private final AssetStore assets;

    public JobController(JobStore store, JobQueueService queue, ObjectMapper mapper, MediaProbe probe,
                          JobValidator validator, ThumbnailService thumbnails, VoiceCatalogService voiceCatalog,
                          AssetStore assets) {
        this.store = store;
        this.queue = queue;
        this.mapper = mapper;
        this.probe = probe;
        this.validator = validator;
        this.thumbnails = thumbnails;
        this.voiceCatalog = voiceCatalog;
        this.assets = assets;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("safeVoices", voiceCatalog.all().stream()
                .filter(v -> v.isCommercialSafe() && voiceCatalog.isDownloaded(v)).toList());
        model.addAttribute("sfxFiles", assets.list(AssetStore.Kind.SFX));
        model.addAttribute("musicFiles", assets.list(AssetStore.Kind.MUSIC));
        return "index";
    }

    @GetMapping("/jobs")
    public String jobs(Model model) {
        model.addAttribute("jobs", store.all());
        return "jobs";
    }

    @GetMapping("/jobs/{id}")
    public String jobDetail(@PathVariable String id, Model model) throws IOException {
        Job job = store.find(id).orElseThrow();
        model.addAttribute("job", job);
        Path instructions = store.inputDir(id).resolve("instructions.json");
        model.addAttribute("instructionsJson",
                Files.isRegularFile(instructions) ? Files.readString(instructions) : "{}");
        return "job";
    }

    /** Polled by the job page's JS to update status/warnings/downloads live, without a full reload. */
    @GetMapping("/jobs/{id}/status")
    @ResponseBody
    public Job status(@PathVariable String id) {
        return store.find(id).orElseThrow();
    }

    @PostMapping("/jobs")
    public String submit(@RequestParam("video") MultipartFile video,
                          @RequestParam(value = "overlayVideoGreen", required = false) MultipartFile overlayVideoGreen,
                          @RequestParam(value = "overlayVideoBlue", required = false) MultipartFile overlayVideoBlue,
                          @RequestParam(value = "overlayVideoRed", required = false) MultipartFile overlayVideoRed,
                          @RequestParam("instructions") MultipartFile instructions,
                          @RequestParam(value = "label", required = false) String label,
                          MultipartHttpServletRequest multipartRequest,
                          RedirectAttributes redirect) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String sourceVideoName = "source" + extensionOf(video.getOriginalFilename());

        Path inputDir = store.inputDir(id);
        Files.createDirectories(inputDir);
        Files.createDirectories(store.workDir(id));

        // transferTo() resolves a relative File against the servlet container's temp dir, not the
        // app's working directory - must be absolute, since app.paths.data defaults to "./data".
        Path videoPath = inputDir.resolve(sourceVideoName).toAbsolutePath();
        video.transferTo(videoPath.toFile());
        instructions.transferTo(inputDir.resolve("instructions.json").toAbsolutePath().toFile());

        Map<String, String> overlayVideoNames = new LinkedHashMap<>();
        saveOverlayIfPresent(inputDir, "green", overlayVideoGreen, overlayVideoNames);
        saveOverlayIfPresent(inputDir, "blue", overlayVideoBlue, overlayVideoNames);
        saveOverlayIfPresent(inputDir, "red", overlayVideoRed, overlayVideoNames);

        // "Incrustations" image elements are a dynamic, unbounded list (unlike the fixed 3 screen
        // colors above), so they arrive as fields keyed "overlayImage_<elementId>" instead of fixed
        // @RequestParam names - read straight off the raw multipart request for those.
        Map<String, String> overlayImageNames = new LinkedHashMap<>();
        saveOverlayImages(inputDir, multipartRequest, overlayImageNames);

        extractThumbnailBestEffort(id, videoPath);

        JobRequest request = mapper.readValue(inputDir.resolve("instructions.json").toFile(), JobRequest.class);
        Job job = newJob(id, sourceVideoName, overlayVideoNames, overlayImageNames, label, request);
        store.save(job);
        queue.submit(id);

        redirect.addAttribute("id", id);
        return "redirect:/jobs/{id}";
    }

    private void saveOverlayIfPresent(Path inputDir, String color, MultipartFile file,
                                       Map<String, String> overlayVideoNames) throws IOException {
        if (file == null || file.isEmpty()) return;
        String name = "overlay_" + color + extensionOf(file.getOriginalFilename());
        file.transferTo(inputDir.resolve(name).toAbsolutePath().toFile());
        overlayVideoNames.put(color, name);
    }

    private void saveOverlayImages(Path inputDir, MultipartHttpServletRequest multipartRequest,
                                    Map<String, String> overlayImageNames) throws IOException {
        for (Map.Entry<String, List<MultipartFile>> e : multipartRequest.getMultiFileMap().entrySet()) {
            if (!e.getKey().startsWith("overlayImage_")) continue;
            String elementId = e.getKey().substring("overlayImage_".length());
            MultipartFile file = e.getValue().isEmpty() ? null : e.getValue().get(0);
            if (file == null || file.isEmpty()) continue;
            String name = "sticker_" + elementId + extensionOf(file.getOriginalFilename());
            file.transferTo(inputDir.resolve(name).toAbsolutePath().toFile());
            overlayImageNames.put(elementId, name);
        }
    }

    /** Checks a video + instructions pair without creating a job - the "Validate" button's endpoint. */
    @PostMapping("/jobs/validate")
    @ResponseBody
    public Map<String, Object> validateJob(@RequestParam("video") MultipartFile video,
                                            @RequestParam("instructions") MultipartFile instructions) {
        Map<String, Object> result = new LinkedHashMap<>();
        Path tmp = null;
        try {
            JobRequest request = mapper.readValue(instructions.getInputStream(), JobRequest.class);

            tmp = Files.createTempFile("rstms-validate-", extensionOf(video.getOriginalFilename()));
            video.transferTo(tmp.toAbsolutePath().toFile());
            double videoDuration = probe.durationSeconds(tmp);

            JobValidator.Result r = validator.check(request, videoDuration);
            result.put("ok", r.ok());
            result.put("errors", r.errors());
            result.put("warnings", r.warnings());
            result.put("videoDurationSeconds", videoDuration);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("errors", List.of("Could not validate: " + (e.getMessage() == null ? e.toString() : e.getMessage())));
            result.put("warnings", List.of());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
            }
        }
        return result;
    }

    /** Resubmits with edited instructions, reusing the original job's already-uploaded video - no re-upload needed. */
    @PostMapping("/jobs/{id}/resubmit")
    public String resubmit(@PathVariable String id,
                            @RequestParam("instructions") String instructionsJson,
                            RedirectAttributes redirect) throws Exception {
        Job old = store.find(id).orElseThrow();
        String newId = UUID.randomUUID().toString().substring(0, 8);

        Path oldVideo = store.inputDir(id).resolve(old.getSourceVideoName());
        Path newInput = store.inputDir(newId);
        Files.createDirectories(newInput);
        Files.createDirectories(store.workDir(newId));
        Files.copy(oldVideo, newInput.resolve(old.getSourceVideoName()), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(newInput.resolve("instructions.json").toAbsolutePath(), instructionsJson);

        for (Map.Entry<String, String> e : old.getOverlayVideoNames().entrySet()) {
            Path oldOverlay = store.inputDir(id).resolve(e.getValue());
            if (Files.isRegularFile(oldOverlay)) {
                Files.copy(oldOverlay, newInput.resolve(e.getValue()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        for (Map.Entry<String, String> e : old.getOverlayImageNames().entrySet()) {
            Path oldImage = store.inputDir(id).resolve(e.getValue());
            if (Files.isRegularFile(oldImage)) {
                Files.copy(oldImage, newInput.resolve(e.getValue()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path oldThumb = store.workDir(id).resolve("thumb.jpg");
        if (Files.isRegularFile(oldThumb)) {
            Files.copy(oldThumb, store.workDir(newId).resolve("thumb.jpg"), StandardCopyOption.REPLACE_EXISTING);
        }

        JobRequest request = mapper.readValue(instructionsJson, JobRequest.class);
        Job job = newJob(newId, old.getSourceVideoName(), old.getOverlayVideoNames(), old.getOverlayImageNames(),
                old.getLabel(), request);
        store.save(job);
        queue.submit(newId);

        redirect.addAttribute("id", newId);
        return "redirect:/jobs/{id}";
    }

    private Job newJob(String id, String sourceVideoName, Map<String, String> overlayVideoNames,
                        Map<String, String> overlayImageNames, String label, JobRequest request) {
        Job job = new Job();
        job.setId(id);
        job.setLabel(label == null || label.isBlank() ? null : label.trim());
        job.setStatus(JobStatus.QUEUED);
        job.setStage("en attente");
        job.setSourceVideoName(sourceVideoName);
        job.setOverlayVideoNames(overlayVideoNames);
        job.setOverlayImageNames(overlayImageNames);
        job.setCreatedAt(Instant.now());
        job.setUpdatedAt(Instant.now());
        job.setPlatforms(request.getPlatforms());
        return job;
    }

    private void extractThumbnailBestEffort(String id, Path videoPath) {
        try {
            thumbnails.extract(videoPath, store.workDir(id).resolve("thumb.jpg"));
        } catch (Exception e) {
            log.warn("Could not extract a thumbnail for job {}: {}", id, e.getMessage());
        }
    }

    @GetMapping("/jobs/{id}/thumbnail")
    @ResponseBody
    public ResponseEntity<Resource> thumbnail(@PathVariable String id) {
        Path thumb = store.workDir(id).resolve("thumb.jpg");
        if (!Files.isRegularFile(thumb)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(new FileSystemResource(thumb));
    }

    /** Streams the composed-but-not-yet-platform-exported master for in-browser review, with Range support so the player can seek. */
    @GetMapping("/jobs/{id}/preview")
    @ResponseBody
    public ResponseEntity<ResourceRegion> preview(@PathVariable String id,
                                                   @RequestHeader HttpHeaders headers) throws Exception {
        Path master = store.workDir(id).resolve("master.mp4");
        if (!Files.isRegularFile(master)) {
            return ResponseEntity.notFound().build();
        }
        UrlResource video = new UrlResource(master.toUri());
        long contentLength = video.contentLength();
        List<HttpRange> ranges = headers.getRange();

        ResourceRegion region;
        if (ranges.isEmpty()) {
            long rangeLength = Math.min(1024 * 1024, contentLength);
            region = new ResourceRegion(video, 0, rangeLength);
        } else {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = range.getRangeEnd(contentLength);
            long rangeLength = Math.min(2 * 1024 * 1024, end - start + 1);
            region = new ResourceRegion(video, start, rangeLength);
        }

        return ResponseEntity.status(ranges.isEmpty() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(region);
    }

    @PostMapping("/jobs/{id}/export")
    public String triggerExport(@PathVariable String id) {
        store.find(id).orElseThrow();
        queue.export(id);
        return "redirect:/jobs/" + id;
    }

    @GetMapping("/jobs/{id}/download/{platform}")
    @ResponseBody
    public ResponseEntity<Resource> download(@PathVariable String id, @PathVariable String platform) {
        Job job = store.find(id).orElseThrow();
        String filename = job.getOutputFiles().get(platform);
        if (filename == null) {
            return ResponseEntity.notFound().build();
        }
        Path file = store.outputDir(id).resolve(filename);
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + "_" + platform + ".mp4\"")
                .body(resource);
    }

    @GetMapping("/jobs/{id}/download-all")
    @ResponseBody
    public ResponseEntity<StreamingResponseBody> downloadAll(@PathVariable String id) {
        Job job = store.find(id).orElseThrow();
        Path outDir = store.outputDir(id);
        StreamingResponseBody body = out -> {
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                for (var entry : job.getOutputFiles().entrySet()) {
                    Path file = outDir.resolve(entry.getValue());
                    if (!Files.isRegularFile(file)) continue;
                    zos.putNextEntry(new ZipEntry(entry.getKey() + ".mp4"));
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + "_all_platforms.zip\"")
                .body(body);
    }

    private static String extensionOf(String filename) {
        if (filename == null) return ".mp4";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : ".mp4";
    }
}

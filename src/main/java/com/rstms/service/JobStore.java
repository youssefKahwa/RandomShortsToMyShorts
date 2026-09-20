package com.rstms.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rstms.config.AppProperties;
import com.rstms.model.Job;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Filesystem-backed job registry - one job.json per job dir, reloaded into memory at startup.
 * No database: this is a single-user local app, and yt-auto's checkpoint-file approach proved
 * this is plenty durable for that.
 */
@Component
public class JobStore {

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public JobStore(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        load();
    }

    public Path jobDir(String id) { return Path.of(props.getPaths().getData(), "jobs", id); }
    public Path inputDir(String id) { return jobDir(id).resolve("input"); }
    public Path workDir(String id) { return jobDir(id).resolve("work"); }
    public Path outputDir(String id) { return jobDir(id).resolve("output"); }

    public void save(Job job) {
        jobs.put(job.getId(), job);
        try {
            Path dir = jobDir(job.getId());
            Files.createDirectories(dir);
            mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("job.json").toFile(), job);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save job " + job.getId(), e);
        }
    }

    public Optional<Job> find(String id) { return Optional.ofNullable(jobs.get(id)); }

    public List<Job> all() {
        List<Job> list = new ArrayList<>(jobs.values());
        list.sort(Comparator.comparing(Job::getCreatedAt).reversed());
        return list;
    }

    private void load() {
        Path root = Path.of(props.getPaths().getData(), "jobs");
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path jobJson = dir.resolve("job.json");
                if (Files.isRegularFile(jobJson)) {
                    try {
                        Job job = mapper.readValue(jobJson.toFile(), Job.class);
                        jobs.put(job.getId(), job);
                    } catch (IOException e) {
                        // Skip a corrupt job directory rather than fail the whole app's startup.
                    }
                }
            });
        } catch (IOException ignored) { }
    }
}

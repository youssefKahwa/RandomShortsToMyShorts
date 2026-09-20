package com.rstms.service;

import com.rstms.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** A small shared library of reusable sound effects and music beds, referenced by filename from instructions.json. */
@Component
public class AssetStore {

    public enum Kind { SFX, MUSIC }

    private final AppProperties props;

    public AssetStore(AppProperties props) {
        this.props = props;
    }

    private Path dir(Kind kind) {
        return Path.of(props.getPaths().getAssets(), kind == Kind.SFX ? "sfx" : "music");
    }

    public Path resolve(Kind kind, String filename) {
        Path dir = dir(kind);
        Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir.normalize())) {
            throw new IllegalArgumentException("Nom de fichier invalide : " + filename);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    (kind == Kind.SFX ? "Fichier d'effet sonore" : "Fichier de musique") + " introuvable : '" + filename
                    + "'. Envoyez-le d'abord sur la page Ressources.");
        }
        return file;
    }

    public void save(Kind kind, MultipartFile upload) {
        try {
            Path dir = dir(kind);
            Files.createDirectories(dir);
            String filename = Path.of(upload.getOriginalFilename()).getFileName().toString();
            upload.transferTo(dir.resolve(filename).toAbsolutePath().toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save asset " + upload.getOriginalFilename(), e);
        }
    }

    public List<String> list(Kind kind) {
        Path dir = dir(kind);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = new ArrayList<>();
            files.filter(Files::isRegularFile).forEach(p -> names.add(p.getFileName().toString()));
            names.sort(Comparator.naturalOrder());
            return names;
        } catch (IOException e) {
            return List.of();
        }
    }
}

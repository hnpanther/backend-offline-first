package com.hnp.backendofflinefirst.service.importjob;

import com.hnp.backendofflinefirst.config.ImportStorageProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImportFileStorageService {

    private final ImportStorageProperties properties;

    @PostConstruct
    void logStorageConfig() {
        Path dir = storageDirectory();
        boolean exists = Files.exists(dir);
        boolean writable = exists ? Files.isWritable(dir) : parentWritable(dir);
        log.info("[IMPORT_STORAGE] configured storagePath={} resolvedDir={} exists={} writable={}",
                properties.getStoragePath(), dir, exists, writable);
    }

    public Path store(String jobUuid, MultipartFile file) throws IOException {
        Path dir = storageDirectory();
        log.info("[IMPORT_STORAGE] store start jobUuid={} originalFile={} uploadSize={} bytes targetDir={}",
                jobUuid, file.getOriginalFilename(), file.getSize(), dir);

        Files.createDirectories(dir);
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import.xlsx";
        String safeName = original.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        Path target = dir.resolve(jobUuid + "-" + safeName).toAbsolutePath().normalize();
        try (var in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        long sizeOnDisk = Files.size(target);
        log.info("[IMPORT_STORAGE] store done jobUuid={} absolutePath={} sizeOnDisk={} bytes",
                jobUuid, target, sizeOnDisk);
        return target;
    }

    public void deleteQuietly(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            log.debug("[IMPORT_STORAGE] delete skipped — empty path");
            return;
        }
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        try {
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                log.info("[IMPORT_STORAGE] deleted path={}", path);
            } else {
                log.warn("[IMPORT_STORAGE] delete skipped — file not found: path={}", path);
            }
        } catch (IOException e) {
            log.warn("[IMPORT_STORAGE] delete failed path={}: {}", path, e.getMessage());
        }
    }

    public Path storageDirectory() {
        return Path.of(properties.getStoragePath()).toAbsolutePath().normalize();
    }

    private static boolean parentWritable(Path dir) {
        Path parent = dir.getParent();
        return parent != null && Files.exists(parent) && Files.isWritable(parent);
    }
}

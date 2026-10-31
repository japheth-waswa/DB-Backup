package com.zurimate.appbackup.service.backup;

import com.zurimate.appbackup.config.BackupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps the newest {@code app.backup.retention.keep-last} archives of each kind in a destination.
 * Only files that match the archive naming scheme are ever touched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionService {
    private static final Pattern ARCHIVE = Pattern.compile("^(db|other)-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}\\.zip$");

    private final BackupProperties properties;

    public int apply(Path destinationDir) {
        int keepLast = properties.retention().keepLast();
        if (keepLast <= 0 || Files.notExists(destinationDir)) return 0;
        return prune(destinationDir, "db-", keepLast) + prune(destinationDir, "other-", keepLast);
    }

    private int prune(Path dir, String prefix, int keepLast) {
        List<Path> archives;
        try (Stream<Path> files = Files.list(dir)) {
            archives = files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && ARCHIVE.matcher(name).matches();
                    })
                    // names embed the timestamp, so lexical order is chronological
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            log.error("Could not list {} for retention", dir, e);
            return 0;
        }

        int deleted = 0;
        for (Path old : archives.stream().skip(keepLast).toList()) {
            try {
                Files.deleteIfExists(old);
                Files.deleteIfExists(old.resolveSibling(old.getFileName() + ArchiveService.CHECKSUM_SUFFIX));
                deleted++;
                log.info("Retention removed {}", old);
            } catch (IOException e) {
                log.error("Retention could not delete {}", old, e);
            }
        }
        return deleted;
    }
}

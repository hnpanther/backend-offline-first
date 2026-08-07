package com.hnp.backendofflinefirst.service;

import com.hnp.backendofflinefirst.dto.AttachmentSweepProgress;
import com.hnp.backendofflinefirst.logging.BusinessEventLogger;
import com.hnp.backendofflinefirst.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deletes attachment files that no database row refers to.
 *
 * <h2>Why orphans exist at all</h2>
 * The row is the source of truth and the file is a satellite, so anything that removes a row
 * without going through {@link AttachmentService#delete} leaves the bytes behind:
 * <ul>
 *   <li><b>A deleted log sheet.</b> {@code attachments.log_sheet_id} is {@code ON DELETE
 *       CASCADE}, so the rows vanish inside the database and nothing ever tells the filesystem.
 *       This is the common case.</li>
 *   <li><b>A crash mid-upload.</b> {@link AttachmentStorageService#store} writes the file before
 *       the transaction commits; if the process dies in between, the file exists and the row
 *       never will.</li>
 *   <li><b>A database restore to an earlier point.</b> Rows go back in time, files do not.</li>
 * </ul>
 * None of these are errors to prevent — they are the normal cost of keeping bytes out of the
 * database, and this job is the other half of that trade.
 *
 * <h2>The grace period is the whole safety design</h2>
 * A file younger than {@code app.attachments.sweep.grace-hours} is <b>never</b> deleted, even
 * with no row. Between {@code store()} writing the bytes and the transaction committing there
 * is a window where a perfectly good upload looks exactly like an orphan; a sweep running in
 * that window would delete a file an operator had just captured, and the row would then point
 * at nothing. Twenty-four hours is enormously more than that window needs, which is the point —
 * the cost of waiting is some dead bytes for a day, and the cost of being wrong is lost
 * evidence.
 *
 * <p>The reverse case — a row whose file is missing — is <b>counted and reported, never
 * repaired</b>. Deleting those rows would erase the only remaining record that something was
 * lost, exactly when an administrator most needs to know.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentSweepService {

    /** Keys are checked against the database in batches of this size rather than one by one. */
    private static final int LOOKUP_BATCH = 500;

    private final AttachmentRepository attachmentRepository;
    private final AttachmentStorageService storageService;
    private final BusinessEventLogger businessEventLogger;

    @Value("${app.attachments.sweep.grace-hours:24}")
    private int graceHours;

    @Value("${app.attachments.sweep.enabled:true}")
    private boolean scheduledSweepEnabled;

    private final AtomicReference<AttachmentSweepProgress> progress =
            new AtomicReference<>(AttachmentSweepProgress.idle());

    private final ExecutorService sweepExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "attachment-sweep");
        t.setDaemon(true);
        return t;
    });

    public AttachmentSweepProgress getProgress() {
        return progress.get();
    }

    public boolean isRunning() {
        return progress.get().isRunning();
    }

    public int getGraceHours() {
        return graceHours;
    }

    /**
     * Counts what a sweep would remove, without removing anything.
     *
     * <p>Surfaced on the Settings page so an administrator can see whether running it is worth
     * anything before starting a job that walks the whole storage root.
     */
    public SweepEstimate estimate() {
        Counters counters = new Counters();
        try {
            scan(counters, false, null);
        } catch (IOException e) {
            log.warn("Attachment sweep estimate failed: {}", e.getMessage());
        }
        return new SweepEstimate(counters.scanned, counters.orphans, counters.orphanBytes,
                countRowsWithMissingFiles());
    }

    /** Rows pointing at a file that is not on disk. Reported for attention, never auto-fixed. */
    public long countRowsWithMissingFiles() {
        return attachmentRepository.findAll().stream()
                .filter(a -> a.getStorageKey() == null || !storageService.exists(a.getStorageKey()))
                .count();
    }

    /** Starts a sweep on the background executor. Only one may run at a time. */
    public synchronized void startSweep() {
        if (isRunning()) {
            throw new IllegalStateException("An attachment sweep is already running.");
        }
        progress.set(AttachmentSweepProgress.running(graceHours));
        sweepExecutor.submit(this::runSweep);
    }

    public void requestCancel() {
        AttachmentSweepProgress current = progress.get();
        if (!current.isRunning()) {
            throw new IllegalStateException("No attachment sweep is running.");
        }
        current.getCancelRequested().set(true);
    }

    /**
     * Scheduled run.
     *
     * <p>Enabled by default because orphans accumulate silently — nobody notices a slowly filling
     * disk until it is full. It is safe to leave on: the grace period means a scheduled pass can
     * only ever touch files that have been unreferenced for a full day.
     */
    @Scheduled(fixedDelayString = "${app.attachments.sweep.interval-ms:86400000}",
            initialDelayString = "${app.attachments.sweep.initial-delay-ms:600000}")
    public void scheduledSweep() {
        if (!scheduledSweepEnabled || isRunning()) {
            return;
        }
        progress.set(AttachmentSweepProgress.running(graceHours));
        runSweep();
    }

    private void runSweep() {
        AttachmentSweepProgress current = progress.get();
        Counters counters = new Counters();
        try {
            scan(counters, true, current);
            counters.missingRows = countRowsWithMissingFiles();

            if (counters.deleted > 0) {
                storageService.pruneEmptyDirectories();
            }

            AttachmentSweepProgress withCounts = current.withCounts(
                    counters.scanned, counters.deleted, counters.reclaimedBytes, counters.missingRows);

            if (current.getCancelRequested().get()) {
                progress.set(AttachmentSweepProgress.cancelled(withCounts));
                return;
            }
            progress.set(AttachmentSweepProgress.completed(withCounts));

            if (counters.deleted > 0 || counters.missingRows > 0) {
                log.info("Attachment sweep: scanned={} deleted={} reclaimedBytes={} rowsMissingFile={}",
                        counters.scanned, counters.deleted, counters.reclaimedBytes, counters.missingRows);
                businessEventLogger.schedulerRun("attachment-sweep-completed", (int) counters.deleted);
            }
        } catch (Exception e) {
            log.error("Attachment sweep failed", e);
            progress.set(AttachmentSweepProgress.failed(
                    current.withCounts(counters.scanned, counters.deleted,
                            counters.reclaimedBytes, counters.missingRows),
                    e.getMessage()));
        }
    }

    /**
     * Walks the storage root, batching the "does a row reference this key?" question.
     *
     * <p>Batching matters at scale: a year of a 50-asset daily sheet is a six-figure file count,
     * and one query per file would be a six-figure round trip. Files are buffered up to
     * {@link #LOOKUP_BATCH} and resolved with a single {@code IN} query.
     */
    private void scan(Counters counters, boolean delete, AttachmentSweepProgress current)
            throws IOException {
        long cutoff = System.currentTimeMillis() - graceHours * 3_600_000L;
        List<AttachmentStorageService.StoredFile> batch = new ArrayList<>(LOOKUP_BATCH);

        storageService.forEachStoredFile(file -> {
            if (current != null && current.getCancelRequested().get()) {
                return false;
            }
            counters.scanned++;
            // Too young to judge: it may belong to an upload whose transaction has not committed.
            if (file.lastModifiedMs() > cutoff) {
                return true;
            }
            batch.add(file);
            if (batch.size() >= LOOKUP_BATCH) {
                processBatch(batch, counters, delete);
                batch.clear();
            }
            return true;
        });
        if (!batch.isEmpty()) {
            processBatch(batch, counters, delete);
        }
    }

    private void processBatch(List<AttachmentStorageService.StoredFile> batch, Counters counters,
                              boolean delete) {
        Set<String> keys = new HashSet<>();
        for (AttachmentStorageService.StoredFile f : batch) {
            keys.add(f.storageKey());
        }
        Set<String> referenced = new HashSet<>(attachmentRepository.findStorageKeysIn(keys));

        for (AttachmentStorageService.StoredFile f : batch) {
            if (referenced.contains(f.storageKey())) {
                continue;
            }
            counters.orphans++;
            counters.orphanBytes += f.sizeBytes();
            if (delete) {
                storageService.delete(f.storageKey());
                counters.deleted++;
                counters.reclaimedBytes += f.sizeBytes();
            }
        }
    }

    /** What a sweep would do, for the Settings page. */
    public record SweepEstimate(long scannedCount, long orphanCount, long orphanBytes,
                                long rowsWithMissingFile) {}

    private static final class Counters {
        long scanned;
        long orphans;
        long orphanBytes;
        long deleted;
        long reclaimedBytes;
        long missingRows;
    }
}

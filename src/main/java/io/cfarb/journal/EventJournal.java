package io.cfarb.journal;

import io.cfarb.metrics.BotMetrics;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.LockSupport;
import org.jboss.logging.Logger;
import org.jctools.queues.MpscArrayQueue;

/**
 * Append-only NDJSON journal — {@code opportunity}, {@code cycle}, {@code broken_cycle},
 * {@code risk_trip}, {@code latency_snapshot} events (cf-arb-bot-plan.md §8). MPSC ring
 * (producers: the detector thread and the executor thread; one dedicated writer thread) so
 * journaling never blocks the hot path — a full ring is dropped and counted
 * ({@code recorder-service} non-negotiable #2: "a dropped frame that isn't counted is a lie"),
 * never backpressured onto a caller.
 *
 * <p>Hourly-rotated plain files under {@code cf-bot.journal.dir}; S3 sync
 * ({@code cf-bot.journal.s3-bucket}) is intentionally NOT implemented here — it needs the AWS SDK
 * (not yet a project dependency) and a real bucket/credentials to test against, neither of which
 * exist in this build environment. Wire it in Phase 1 once Terraform has actually provisioned the
 * bucket (cf-arb-bot-plan.md §7); until then, an empty (or non-empty but unused) {@code s3-bucket}
 * has no effect and this class only ever writes locally.
 */
public final class EventJournal {

    private static final Logger LOG = Logger.getLogger(EventJournal.class);
    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HH").withZone(ZoneOffset.UTC);

    // cf-arb-bot-review-plan.md Tier 2 step 2.6: genuinely bounded now (MpscArrayQueue, not the
    // previous MpscUnboundedArrayQueue) -- an unbounded queue meant the drop-and-count path in
    // write() below could never actually be exercised, and a sustained I/O stall could grow the
    // queue without limit.
    private final MpscArrayQueue<String> queue = new MpscArrayQueue<>(4096);
    private final Path dir;
    private final BotMetrics metrics;
    private volatile boolean running;
    private Thread writerThread;
    private String currentHourKey;
    private BufferedWriter currentWriter;

    public EventJournal(Path dir, BotMetrics metrics) {
        this.dir = dir;
        this.metrics = metrics;
    }

    public void start() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create journal dir " + dir, e);
        }
        running = true;
        writerThread = new Thread(this::runLoop, "cf-arb-journal-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    public void stop() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
        }
        closeCurrentWriter();
    }

    /** Enqueue one pre-serialized NDJSON line (without trailing newline). Never blocks; drops and
     * counts under sustained backpressure rather than applying it to the caller. */
    public void write(String jsonLine) {
        if (!queue.offer(jsonLine)) {
            metrics.recordJournalDrop();
        }
    }

    private void runLoop() {
        while (running || !queue.isEmpty()) {
            String line = queue.poll();
            if (line == null) {
                LockSupport.parkNanos(1_000_000); // 1ms
                continue;
            }
            try {
                appendLine(line);
            } catch (IOException e) {
                LOG.warnf("journal write failed: %s", e.toString());
            }
        }
        closeCurrentWriter();
    }

    private void appendLine(String line) throws IOException {
        String hourKey = HOUR_FMT.format(Instant.now());
        if (!hourKey.equals(currentHourKey)) {
            closeCurrentWriter();
            Path file = dir.resolve("cf-arb-bot-" + hourKey + ".ndjson");
            currentWriter = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND),
                    StandardCharsets.UTF_8));
            currentHourKey = hourKey;
        }
        currentWriter.write(line);
        currentWriter.newLine();
        currentWriter.flush(); // durability over throughput -- trade/opportunity events are low-rate (§1e/§1f: single digits to tens per hour)
    }

    private void closeCurrentWriter() {
        if (currentWriter != null) {
            try {
                currentWriter.close();
            } catch (IOException ignored) {
                // best-effort on shutdown
            }
            currentWriter = null;
            currentHourKey = null;
        }
    }
}

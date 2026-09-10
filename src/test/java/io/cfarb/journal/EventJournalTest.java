package io.cfarb.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.metrics.BotMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code cf-bot.observability.echo-events} console echo must not disturb the append path —
 * the line still lands in the hourly NDJSON file exactly once. */
class EventJournalTest {

    @TempDir
    Path tempDir;

    @Test
    void echoToConsoleStillAppendsEachLineOnce() throws Exception {
        BotMetrics metrics = new BotMetrics(new SimpleMeterRegistry());
        EventJournal journal = new EventJournal(tempDir, metrics, true);
        journal.start();
        try {
            journal.write(JournalEvents.opportunity("usdt-btc-xrp-fwd", 7.5, 9.1, 100_00000000L, true, null));
            journal.write(JournalEvents.riskTrip("test-reason", 90_00000000L));
            List<String> lines = awaitLines(2);
            assertEquals(2, lines.size(), lines.toString());
            assertTrue(lines.get(0).contains("\"type\":\"opportunity\""), lines.get(0));
            assertTrue(lines.get(0).contains("\"fired\":true"), lines.get(0));
            assertTrue(lines.get(1).contains("\"type\":\"risk_trip\""), lines.get(1));
        } finally {
            journal.stop();
        }
    }

    private List<String> awaitLines(int expected) throws IOException, InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<String> lines = readAll();
            if (lines.size() >= expected) {
                return lines;
            }
            Thread.sleep(10);
        }
        return readAll();
    }

    private List<String> readAll() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".ndjson"))
                    .sorted()
                    .flatMap(p -> {
                        try {
                            return Files.readAllLines(p).stream();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        }
    }
}

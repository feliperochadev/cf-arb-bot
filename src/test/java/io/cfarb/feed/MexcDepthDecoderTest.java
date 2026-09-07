package io.cfarb.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.util.FixedPoint;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Cross-checks {@link MexcDepthDecoder} field-for-field against real captured MEXC depth frames,
 * with the oracle values produced independently by the Python reference decoder
 * ({@code cf-arb-poc/cfarb/venues/mexc.py} + {@code mexc_pb.py}) on the SAME bytes
 * (cf-arb-bot-plan.md §11 verification item 1: "Cross-check field-for-field against
 * cfarb/venues/mexc.py"). Fixtures extracted from
 * {@code recorder-service/data/mexc/2026-08-29/mexc-20260829-00.seg.zst} — never a live exchange
 * in CI (rule Q10).
 */
class MexcDepthDecoderTest {

    private record Oracle(String file, String symbol, long sendTimeUs, long fromVersion, long toVersion,
                           double bestBidPx, double bestBidQty, double bestAskPx, double bestAskQty,
                           int numBids, int numAsks) {
    }

    // Extracted by scratchpad/extract fixtures script from a real captured segment; see
    // src/test/resources/fixtures/mexc_depth_oracle.json for the machine-readable copy.
    private static final Oracle[] ORACLE = {
            new Oracle("mexc_depth_00.pb.bin", "BTCUSDT", 1787961599952000L, 80111665779L, 80111665794L,
                    77825.41, 0.0, 77855.52, 0.0, 6, 8),
            new Oracle("mexc_depth_01.pb.bin", "ETHUSDT", 1787961599953000L, 61568244211L, 61568244231L,
                    2442.27, 0.0, 2443.22, 2.37006, 9, 9),
            new Oracle("mexc_depth_02.pb.bin", "XRPUSDC", 1787961599969000L, 4925475318L, 4925475319L,
                    1.383, 0.0, 1.3842, 0.0, 1, 1),
            new Oracle("mexc_depth_03.pb.bin", "BTCUSDC", 1787961600017000L, 23803432505L, 23803432537L,
                    77347.3, 0.0, 77904.09, 0.00021, 1, 31),
            new Oracle("mexc_depth_04.pb.bin", "BTCUSDT", 1787961600052000L, 80111665795L, 80111665862L,
                    77833.25, 9.00689501, 77834.26, 0.0, 18, 21),
            new Oracle("mexc_depth_05.pb.bin", "ETHUSDT", 1787961600053000L, 61568244232L, 61568244269L,
                    2442.22, 0.0, 2443.25, 0.0, 12, 11),
            new Oracle("mexc_depth_06.pb.bin", "XRPUSDT", 1787961600062000L, 11245246685L, 11245246698L,
                    1.3837, 565.1, 1.3839, 0.0, 6, 5),
            new Oracle("mexc_depth_07.pb.bin", "XRPUSDC", 1787961600068000L, 4925475320L, 4925475330L,
                    1.383, 350.94, 1.3842, 282.11, 2, 7),
    };

    private static byte[] loadFixture(String name) throws IOException {
        try (InputStream in = MexcDepthDecoderTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IOException("fixture not found: " + name);
            return in.readAllBytes();
        }
    }

    @Test
    void decodesEveryRealCapturedFixtureExactly() throws IOException {
        var top = new ProtobufWalker.Cursor();
        var sub = new ProtobufWalker.Cursor();
        var level = new ProtobufWalker.Cursor();
        var out = new MexcDepthDecoder.DepthFrame();

        for (Oracle o : ORACLE) {
            byte[] buf = loadFixture(o.file());
            boolean ok = MexcDepthDecoder.decode(buf, 0, buf.length, top, sub, level, out);
            assertTrue(ok, "expected a genuine depth frame for " + o.file());
            assertTrue(out.valid);

            assertTrue(MexcDepthDecoder.symbolEquals(buf, out.symbolStart, out.symbolEnd, o.symbol()),
                    "symbol mismatch for " + o.file());

            assertEquals(o.sendTimeUs() / 1000, out.sendTimeMs, "sendTimeMs mismatch for " + o.file());
            assertEquals(o.fromVersion(), out.fromVersion, "fromVersion mismatch for " + o.file());
            assertEquals(o.toVersion(), out.toVersion, "toVersion mismatch for " + o.file());

            assertEquals(o.numBids(), out.bidCount, "bidCount mismatch for " + o.file());
            assertEquals(o.numAsks(), out.askCount, "askCount mismatch for " + o.file());

            assertEquals(FixedPoint.fromDouble(o.bestBidPx()), out.bidPx[0], 1, "best bid px for " + o.file());
            assertEquals(FixedPoint.fromDouble(o.bestBidQty()), out.bidQty[0], 1, "best bid qty for " + o.file());
            assertEquals(FixedPoint.fromDouble(o.bestAskPx()), out.askPx[0], 1, "best ask px for " + o.file());
            assertEquals(FixedPoint.fromDouble(o.bestAskQty()), out.askQty[0], 1, "best ask qty for " + o.file());
        }
    }

    @Test
    void rejectsNonDepthControlFrame() {
        // A short JSON PONG reply, as literal bytes -- not valid protobuf tag/length data for our
        // fields, so decode() must return false rather than throwing or fabricating a result.
        byte[] pong = "{\"id\":1,\"code\":0,\"msg\":\"PONG\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var top = new ProtobufWalker.Cursor();
        var sub = new ProtobufWalker.Cursor();
        var level = new ProtobufWalker.Cursor();
        var out = new MexcDepthDecoder.DepthFrame();
        boolean ok = MexcDepthDecoder.decode(pong, 0, pong.length, top, sub, level, out);
        assertFalse(ok);
        assertFalse(out.valid);
    }

    @Test
    void rejectsTruncatedBuffer() {
        var top = new ProtobufWalker.Cursor();
        var sub = new ProtobufWalker.Cursor();
        var level = new ProtobufWalker.Cursor();
        var out = new MexcDepthDecoder.DepthFrame();
        // A length-delimited tag claiming more bytes than are actually present.
        byte[] truncated = {0x0A, 0x7F, 0x01, 0x02, 0x03}; // field 1, LEN, length=127, only 3 bytes follow
        boolean ok = MexcDepthDecoder.decode(truncated, 0, truncated.length, top, sub, level, out);
        assertFalse(ok);
    }
}

package io.cfarb.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cfarb.exec.MexcOrderApi;
import io.cfarb.util.FixedPoint;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * PRE-LIVE-PLAN.md P1-4(b): {@link BalanceReconciler} against a STUBBED {@link MexcOrderApi} —
 * non-negotiable #8, never a live network call in an automated test. The stub only ever returns a
 * hand-built {@code /api/v3/account} JSON body from a completed future; no socket is opened.
 */
class BalanceReconcilerTest {

    /** Minimal stub — {@link BalanceReconciler} only ever calls {@link #account}; every other
     * method throws if accidentally invoked, so a wiring mistake fails loudly rather than silently
     * returning a fake order result. */
    private static final class StubApi implements MexcOrderApi {
        private final String accountJson;

        StubApi(String accountJson) {
            this.accountJson = accountJson;
        }

        @Override
        public CompletableFuture<String> account(long timeoutMs) {
            return CompletableFuture.completedFuture(accountJson);
        }

        @Override
        public CompletableFuture<String> placeOrder(String queryString, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<String> queryOrder(String symbol, String clientOrderId, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<String> listTrades(String symbol, String orderId, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<String> cancelOrder(String symbol, String clientOrderId, long timeoutMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sign(String queryString) {
            throw new UnsupportedOperationException();
        }
    }

    private static String balance(String asset, String free, String locked) {
        return "{\"asset\":\"" + asset + "\",\"free\":\"" + free + "\",\"locked\":\"" + locked + "\"}";
    }

    private static String accountJson(String... balanceEntries) {
        return "{\"balances\":[" + String.join(",", balanceEntries) + "]}";
    }

    @Test
    void aCleanAccountSeedsFromTheLiveAnchorBalance() throws Exception {
        String json = accountJson(
                balance("USDT", "2543.12345678", "0"),
                balance("BTC", "0", "0"));
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(result.clean());
        assertEquals(2543.12345678, FixedPoint.toDouble(result.anchorBalanceFixed()), 1e-8);
    }

    @Test
    void anchorBalanceIncludesBothFreeAndLocked() throws Exception {
        String json = accountJson(balance("USDT", "1000.0", "50.5"));
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertEquals(1050.5, FixedPoint.toDouble(result.anchorBalanceFixed()), 1e-8);
    }

    @Test
    void strandedNonAnchorInventoryIsReportedNotSilentlyDropped() throws Exception {
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                balance("BTC", "0.03", "0")); // real stranded inventory from an interrupted cycle
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(!result.clean());
        assertEquals(1, result.stranded().size());
        assertEquals("BTC", result.stranded().get(0).asset());
        assertEquals(0.03, result.stranded().get(0).balance(), 1e-8);
    }

    @Test
    void aRecognizedStablecoinPeerBelowTheDustThresholdIsNotStranded() throws Exception {
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                balance("USDC", "0.50", "0")); // below the $1.0 dust allowance
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(result.clean());
    }

    @Test
    void aRecognizedStablecoinPeerAboveTheDustThresholdIsStranded() throws Exception {
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                balance("USDC", "5.00", "0")); // above the $1.0 dust allowance
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(!result.clean());
        assertEquals("USDC", result.stranded().get(0).asset());
    }

    @Test
    void aNonStablecoinAssetIsStrandedEvenBelowTheConfiguredDustUsdValue() throws Exception {
        // No live price exists yet at boot -- ANY nonzero non-anchor, non-stablecoin balance is
        // stranded regardless of how small the RAW quantity is (see class javadoc).
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                balance("BTC", "0.00000001", "0"));
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(!result.clean());
        assertEquals("BTC", result.stranded().get(0).asset());
    }

    @Test
    void multipleStrandedAssetsAreAllReported() throws Exception {
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                balance("BTC", "0.001", "0"),
                balance("ETH", "0.05", "0"));
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertEquals(2, result.stranded().size());
    }

    @Test
    void missingAnchorAssetEntryFailsTheBoot() {
        String json = accountJson(balance("BTC", "0.001", "0")); // no USDT entry at all
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(1000));
    }

    @Test
    void unparseableAnchorBalanceFailsTheBoot() {
        String json = "{\"balances\":[{\"asset\":\"USDT\",\"free\":\"not-a-number\",\"locked\":\"0\"}]}";
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(1000));
    }

    @Test
    void unparseableNonAnchorBalanceIsTreatedAsStrandedNotSilentlyDropped() throws Exception {
        String json = accountJson(
                balance("USDT", "2500.0", "0"),
                "{\"asset\":\"BTC\",\"free\":\"garbage\",\"locked\":\"0\"}");
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(json), "USDT", 1.0);

        BalanceReconciler.Result result = reconciler.reconcile(1000);

        assertTrue(!result.clean(), "an unparseable balance must never be silently treated as zero");
        assertEquals("BTC", result.stranded().get(0).asset());
    }

    @Test
    void emptyBalancesArrayIsMissingAnchorAndFailsTheBoot() {
        BalanceReconciler reconciler = new BalanceReconciler(new StubApi(accountJson()), "USDT", 1.0);
        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(1000));
    }
}

package io.cfarb.api;

import io.cfarb.BotService;
import io.cfarb.book.BookRegistry;
import io.cfarb.book.L2Book;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Composite readiness check (cf-arb-bot-review-plan.md Tier 2 step 2.4 / plan §8:
 * {@code GET /q/health/ready} = "feeds healthy, books warm, clock synced, kill switch not
 * tripped"). {@code quarkus-smallrye-health} was already a project dependency with no readiness
 * implementation behind it — this is that implementation. Liveness stays the SmallRye default
 * (trivially up); this bot has no separate liveness failure mode worth modeling yet.
 */
@Readiness
public class ReadinessCheck implements HealthCheck {

    @Inject
    BotService botService;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("cf-arb-bot");

        boolean wsConnected = botService.wsClientConnected();
        boolean wsChurning = botService.wsClientChurning();
        boolean killSwitchTripped = botService.killSwitch().tripped();
        long clockSkewNanos = botService.clockSkewNanos();
        long clockSkewToleranceNanos = botService.clockSkewToleranceNanos();
        boolean clockOk = Math.abs(clockSkewNanos) <= clockSkewToleranceNanos;

        BookRegistry books = botService.bookRegistry();
        boolean allBooksWarm = books != null;
        if (books != null) {
            for (int i = 0; i < books.symbolCount(); i++) {
                L2Book book = books.book(i);
                if (!book.isTrusted()) {
                    allBooksWarm = false;
                    break;
                }
            }
        }

        boolean ready = wsConnected && !wsChurning && allBooksWarm && clockOk && !killSwitchTripped;

        builder.status(ready)
                .withData("wsConnected", wsConnected)
                .withData("wsChurning", wsChurning)
                .withData("allBooksWarm", allBooksWarm)
                .withData("clockSkewMs", Double.toString(clockSkewNanos / 1_000_000.0))
                .withData("clockOk", clockOk)
                .withData("killSwitchTripped", killSwitchTripped);

        return builder.build();
    }
}

package io.cfarb.graph;

import io.cfarb.book.BookRegistry;
import io.cfarb.config.BotConfig;
import io.cfarb.model.Side;
import io.cfarb.model.SymbolFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Config -> resolved {@link Triangle}[] plus the inverted index {@code trianglesBySymbol}: which
 * triangle indices a given symbolIndex participates in. Built ONCE at startup
 * (cf-arb-bot-plan.md §5.1/§4.1 — "a precomputed int[][] trianglesByLeg built once in
 * @PostConstruct... no map lookup, no allocation on the tick path"), so
 * {@code strategy.OpportunityDetector}'s hot-path loop is "for each triangle containing this
 * symbolIndex" with zero lookups.
 */
public final class TriangleRegistry {

    private final Triangle[] triangles;
    /** trianglesBySymbol[symbolIndex] = array of triangle indices touching that symbol. */
    private final int[][] trianglesBySymbol;

    public TriangleRegistry(BotConfig config, BookRegistry books, Map<String, SymbolFilter> filters) {
        this(config.triangles(), config.capital().anchorAsset(), books, filters);
    }

    /** Narrower constructor taking only what this class actually uses -- lets tests exercise
     * triangle-closure validation without mocking the entire {@link BotConfig} interface
     * (cf-arb-bot-review-plan.md "Tests to add": {@code TriangleRegistryTest}). */
    public TriangleRegistry(Map<String, BotConfig.TriangleConfig> triangleConfigs, String anchorAsset,
                             BookRegistry books, Map<String, SymbolFilter> filters) {
        List<Triangle> built = new ArrayList<>();
        for (Map.Entry<String, BotConfig.TriangleConfig> e : triangleConfigs.entrySet()) {
            String name = e.getKey();
            BotConfig.TriangleConfig tc = e.getValue();
            if (!tc.enabled()) {
                continue;
            }
            List<String> legs = tc.legs();
            if (legs.size() != 3) {
                throw new IllegalStateException(
                        "cf-bot.triangles." + name + ".legs must list exactly 3 legs, got " + legs.size());
            }
            int[] symbolIndex = new int[3];
            Side[] sides = new Side[3];
            SymbolFilter[] symFilters = new SymbolFilter[3];
            String[] fromAsset = new String[3];
            String[] toAsset = new String[3];
            for (int i = 0; i < 3; i++) {
                String[] parts = legs.get(i).split(":");
                if (parts.length != 2) {
                    throw new IllegalStateException(
                            "cf-bot.triangles." + name + ".legs[" + i + "] must be SYMBOL:SIDE, got '" + legs.get(i) + "'");
                }
                String symbol = parts[0];
                Side side = Side.valueOf(parts[1]);
                int idx = books.indexOf(symbol);
                if (idx < 0) {
                    throw new IllegalStateException(
                            "cf-bot.triangles." + name + " references symbol '" + symbol
                                    + "' which is not in cf-bot.symbols — add it there first");
                }
                SymbolFilter filter = filters.get(symbol);
                if (filter == null) {
                    throw new IllegalStateException(
                            "no exchange filter loaded for symbol '" + symbol + "' referenced by triangle " + name);
                }
                symbolIndex[i] = idx;
                sides[i] = side;
                symFilters[i] = filter;
                fromAsset[i] = Triangle.legFromAsset(side, filter);
                toAsset[i] = Triangle.legToAsset(side, filter);
            }
            // cf-arb-bot-review-plan.md Tier 1 step 1.4 / new defect 2: nothing previously checked
            // that a configured triangle's legs actually chain together (leg i's toAsset must equal
            // leg i+1's fromAsset) or that it starts and ends at the configured anchor asset. A typo
            // in application.properties would otherwise silently produce a "triangle" whose net_bps
            // compares mismatched currencies.
            if (!fromAsset[0].equals(anchorAsset)) {
                throw new IllegalStateException("cf-bot.triangles." + name + " leg[0] must spend the anchor "
                        + "asset '" + anchorAsset + "' (cf-bot.capital.anchor-asset), but spends '"
                        + fromAsset[0] + "'");
            }
            if (!toAsset[2].equals(anchorAsset)) {
                throw new IllegalStateException("cf-bot.triangles." + name + " leg[2] must return to the anchor "
                        + "asset '" + anchorAsset + "', but returns '" + toAsset[2] + "'");
            }
            for (int i = 0; i < 2; i++) {
                if (!toAsset[i].equals(fromAsset[i + 1])) {
                    throw new IllegalStateException("cf-bot.triangles." + name + " does not chain: leg[" + i
                            + "] produces '" + toAsset[i] + "' but leg[" + (i + 1) + "] spends '"
                            + fromAsset[i + 1] + "'");
                }
            }
            built.add(new Triangle(name, symbolIndex, sides, symFilters, fromAsset, toAsset));
        }
        this.triangles = built.toArray(new Triangle[0]);

        List<List<Integer>> bySymbol = new ArrayList<>();
        for (int s = 0; s < books.symbolCount(); s++) {
            bySymbol.add(new ArrayList<>());
        }
        for (int t = 0; t < triangles.length; t++) {
            for (int symIdx : triangles[t].symbolIndex()) {
                bySymbol.get(symIdx).add(t);
            }
        }
        this.trianglesBySymbol = new int[books.symbolCount()][];
        for (int s = 0; s < books.symbolCount(); s++) {
            List<Integer> list = bySymbol.get(s);
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            trianglesBySymbol[s] = arr;
        }
    }

    public Triangle triangle(int triangleIndex) {
        return triangles[triangleIndex];
    }

    public int triangleCount() {
        return triangles.length;
    }

    /** Zero-allocation hot-path lookup: which triangle indices touch this symbol. */
    public int[] trianglesForSymbol(int symbolIndex) {
        return trianglesBySymbol[symbolIndex];
    }
}

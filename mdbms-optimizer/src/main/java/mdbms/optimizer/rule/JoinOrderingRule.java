package mdbms.optimizer.rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mdbms.models.query.ExpressionTree;
import mdbms.models.query.PhysicalAlgorithm;
import mdbms.models.query.QueryNodeType;
import mdbms.models.query.QueryTree;
import mdbms.optimizer.binder.BindingContext;
import mdbms.optimizer.cost.CardinalityEstimator;
import mdbms.optimizer.cost.ICostEstimator;
import mdbms.optimizer.cost.SystemCostEstimator;

// mencoba berbagai urutan dan pengelompokan join, ambil yang biaya totalnya paling murah.
public class JoinOrderingRule implements IOptimizationRule {
    // Jumlah belahan yang dicoba tumbuh sekitar 3^n (8 tabel = 6.561 belahan).
    // Sampai batas ini masih ringan; di atasnya pakai greedy supaya optimasi tetap cepat.
    static final int MAX_EXHAUSTIVE_INPUTS = 8;

    private final JoinConversionRule conversion = new JoinConversionRule();

    @Override
    public String getRuleName() {
        return "JoinOrdering";
    }

    @Override
    public boolean matches(QueryTree node) {
        return conversion.matches(node);
    }

    @Override
    public QueryTree apply(QueryTree node, OptimizationContext context) {
        if (!matches(node) || context == null) {
            return node;
        }
        BindingContext bindings = context.getBindingContext();
        ICostEstimator estimator = context.getCostEstimator();
        if (estimator == null) {
            estimator = new SystemCostEstimator(context::getStatistics, new CardinalityEstimator());
        }
        List<QueryTree> inputs = new ArrayList<>();
        List<ExpressionTree> remaining = new ArrayList<>();
        conversion.collect(node, inputs, remaining);
        List<Set<String>> tables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (QueryTree input : inputs) {
            Set<String> names = JoinConversionRule.tablesIn(input, bindings);
            // keep natural joins and other multi-relation boundaries opaque, and
            // do not reorder a self join without distinct relation identities.
            if (names.size() != 1 || !Collections.disjoint(seen, names)) {
                return node;
            }
            tables.add(names);
            seen.addAll(names);
        }

        // Include local filters in the starting-table estimates, including those
        // originally written in ON rather than WHERE.
        for (int i = 0; i < inputs.size(); i++) {
            List<ExpressionTree> local = new ArrayList<>();
            for (ExpressionTree predicate : remaining) {
                Set<String> refs = JoinConversionRule.references(predicate, bindings);
                if (refs != null && !refs.isEmpty() && tables.get(i).containsAll(refs)) {
                    local.add(predicate);
                }
            }
            inputs.set(i, selection(inputs.get(i), local));
            remaining.removeAll(local);
        }

        // Cari plan termurah: exhaustive (DP bitmask) kalau tabelnya sedikit, greedy kalau banyak
        QueryTree reordered = inputs.size() <= MAX_EXHAUSTIVE_INPUTS
            ? exhaustive(inputs, tables, remaining, bindings, estimator)
            : greedy(inputs, tables, remaining, bindings, estimator);
        // Plan awal ikut jadi kandidat: hasil reorder hanya dipakai kalau BENAR-BENAR lebih murah.
        // Jadi optimasi tidak pernah bikin plan lebih mahal, dan kalau seri plan awal dipertahankan
        // supaya RuleBasedOptimizer yang menjalankan rule berulang kali tidak bolak-balik.
        return estimator.calculateTotalCost(reordered) < estimator.calculateTotalCost(node) ? reordered : node;
    }

    // Pencarian gaya Selinger (dynamic programming) dengan bitmask.
    
    // Ide utamanya: setiap HIMPUNAN tabel diwakili satu angka (mask), satu bit per tabel.
    //   A = 001, B = 010, C = 100  ->  {A,B} = 011, {B,C} = 110, {A,B,C} = 111
    // best[mask] menyimpan pohon join termurah untuk himpunan tabel itu.
    
    // Untuk mengisi best[mask], himpunannya dibelah jadi dua bagian (kiri dan kanan) dengan
    // semua cara yang mungkin, lalu kedua bagian disambung pakai best[kiri] dan best[kanan]
    // yang sudah dihitung sebelumnya. Karena setiap belahan dicoba di kedua arah, kandidatnya
    // otomatis mencakup tukar sisi (A ⋈ B vs B ⋈ A) dan pengelompokan ulang
    // ((A ⋈ B) ⋈ C vs A ⋈ (B ⋈ C)).
    private static QueryTree exhaustive(List<QueryTree> inputs, List<Set<String>> tables,
                                        List<ExpressionTree> remaining, BindingContext bindings,
                                        ICostEstimator estimator) {
        int n = inputs.size();
        // full = semua bit menyala = himpunan berisi semua tabel, mis. 3 tabel -> 111 (= 7)
        int full = (1 << n) - 1;

        // Ubah tiap kondisi join jadi mask tabel yang disebutnya, mis. a.x = b.y -> 011.
        // Kondisi yang tidak bisa dipetakan ke tabel (mask 0) disisihkan ke leftover
        // dan nanti dipasang sebagai SELECT di paling atas.
        List<ExpressionTree> predicates = new ArrayList<>();
        List<Integer> predicateMasks = new ArrayList<>();
        List<ExpressionTree> leftover = new ArrayList<>();
        for (ExpressionTree predicate : remaining) {
            int mask = maskOf(JoinConversionRule.references(predicate, bindings), tables);
            if (mask == 0) {
                leftover.add(predicate);
            } else {
                predicates.add(predicate);
                predicateMasks.add(mask);
            }
        }

        // Kasus dasar: himpunan berisi satu tabel, pohon terbaiknya ya tabel itu sendiri
        // (sudah termasuk filter lokalnya). best[001] = A, best[010] = B, best[100] = C.
        QueryTree[] best = new QueryTree[full + 1];
        for (int i = 0; i < n; i++) {
            best[1 << i] = inputs.get(i);
        }
        // Isi dari angka kecil ke besar. Subset dari sebuah mask selalu bernilai lebih kecil
        // (mis. 011 < 111), jadi saat best[mask] dihitung, best untuk bagian-bagiannya sudah ada.
        for (int mask = 1; mask <= full; mask++) {
            // himpunan 1 tabel sudah diisi di kasus dasar
            if (Integer.bitCount(mask) < 2) {
                continue;
            }
            QueryTree chosen = null;
            int chosenCost = Integer.MAX_VALUE;
            boolean chosenConnected = false;
            // Telusuri semua subset tidak kosong dari mask sebagai sisi kiri (trik submask),
            // sisi kanan = bit sisanya. Untuk 111: 110|001, 101|010, 100|011, 011|100, 010|101, 001|110.
            for (int left = (mask - 1) & mask; left > 0; left = (left - 1) & mask) {
                int right = mask ^ left;
                // Kondisi dipasang di join ini kalau semua tabelnya ada di himpunan ini
                // DAN menyentuh kedua sisi. Kondisi yang seluruhnya di satu sisi sudah terpasang
                // di dalam best[kiri]/best[kanan], jadi setiap kondisi jatuh di join terendah
                // tempat ia pertama kali bisa dievaluasi dan tidak terpasang dua kali.
                List<ExpressionTree> conditions = new ArrayList<>();
                for (int p = 0; p < predicates.size(); p++) {
                    int refs = predicateMasks.get(p);
                    if ((refs & ~mask) == 0 && (refs & left) != 0 && (refs & right) != 0) {
                        conditions.add(predicates.get(p));
                    }
                }
                boolean connected = !conditions.isEmpty();
                // Hindari cartesian: begitu sudah ada belahan yang punya kondisi penghubung,
                // belahan tanpa kondisi (cartesian) tidak perlu dicoba lagi
                if (chosenConnected && !connected) {
                    continue;
                }
                QueryTree candidate = new QueryTree(connected ? QueryNodeType.JOIN : QueryNodeType.CARTESIAN,
                    PhysicalAlgorithm.DEFAULT, null, conditions, List.of(best[left], best[right]), 0);
                
                    // Hitung Biaya Cost
                int cost = estimator.calculateTotalCost(candidate);
                if (chosen == null || (connected && !chosenConnected) || cost < chosenCost) {
                    chosen = candidate;
                    chosenCost = cost;
                    chosenConnected = connected;
                }
            }
            best[mask] = chosen;
        }
        return selection(best[full], leftover);
    }

    // Cadangan untuk tabel > MAX_EXHAUSTIVE_INPUTS: greedy left-deep. Mulai dari input dengan
    // baris paling sedikit, lalu terus tambahkan input yang terhubung dan hasilnya paling kecil.
    // Lebih cepat tapi tidak menghitung biaya, jadi hasilnya tetap dibandingkan di apply().
    private static QueryTree greedy(List<QueryTree> inputs, List<Set<String>> tables,
                                    List<ExpressionTree> remaining, BindingContext bindings,
                                    ICostEstimator estimator) {
        inputs = new ArrayList<>(inputs);
        tables = new ArrayList<>(tables);
        remaining = new ArrayList<>(remaining);
        int first = 0;
        long smallest = Long.MAX_VALUE;
        for (int i = 0; i < inputs.size(); i++) {
            long rows = estimator.estimateOutput(inputs.get(i)).getEstimatedRows();
            if (rows < smallest) {
                first = i;
                smallest = rows;
            }
        }
        QueryTree result = inputs.remove(first);
        Set<String> available = new HashSet<>(tables.remove(first));
        while (!inputs.isEmpty()) {
            int best = -1;
            long bestRows = Long.MAX_VALUE;
            boolean bestConnected = false;
            QueryTree bestPlan = null;
            List<ExpressionTree> bestConditions = List.of();
            for (int i = 0; i < inputs.size(); i++) {
                Set<String> combined = new HashSet<>(available);
                combined.addAll(tables.get(i));
                List<ExpressionTree> conditions = new ArrayList<>();
                for (ExpressionTree predicate : remaining) {
                    Set<String> refs = JoinConversionRule.references(predicate, bindings);
                    if (refs != null && combined.containsAll(refs)
                        && !Collections.disjoint(refs, available)
                        && !Collections.disjoint(refs, tables.get(i))) {
                        conditions.add(predicate);
                    }
                }
                boolean connected = !conditions.isEmpty();
                QueryTree candidate = new QueryTree(connected ? QueryNodeType.JOIN : QueryNodeType.CARTESIAN,
                    PhysicalAlgorithm.DEFAULT, null, conditions, List.of(result, inputs.get(i)), 0);
                long rows = estimator.estimateOutput(candidate).getEstimatedRows();
                if (best == -1 || (connected && !bestConnected)
                    || (connected == bestConnected && rows < bestRows)) {
                    best = i;
                    bestRows = rows;
                    bestConnected = connected;
                    bestPlan = candidate;
                    bestConditions = conditions;
                }
            }
            result = bestPlan;
            available.addAll(tables.remove(best));
            inputs.remove(best);
            remaining.removeAll(bestConditions);
        }
        return selection(result, remaining);
    }

    // Ubah nama-nama tabel yang disebut kondisi jadi bitmask, mis. {a, b} -> 011.
    // Hasil 0 berarti kondisi tidak bisa dipasang di join (cuma 1 tabel, atau tabelnya tidak dikenal).
    private static int maskOf(Set<String> refs, List<Set<String>> tables) {
        if (refs == null || refs.size() < 2) {
            return 0;
        }
        int mask = 0;
        for (String ref : refs) {
            int owner = -1;
            for (int i = 0; i < tables.size(); i++) {
                if (tables.get(i).contains(ref)) {
                    owner = i;
                    break;
                }
            }
            if (owner < 0) {
                return 0;
            }
            mask |= 1 << owner;
        }
        return mask;
    }

    private static QueryTree selection(QueryTree input, List<ExpressionTree> conditions) {
        return conditions.isEmpty() ? input : new QueryTree(QueryNodeType.SELECT,
            PhysicalAlgorithm.DEFAULT, null, conditions, List.of(input), 0);
    }
}

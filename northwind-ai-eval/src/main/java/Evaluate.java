import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scores generated outputs against reference answers.
 *
 * For every ground-truth question the reference script is executed to get the expected answer, then the
 * generated answer is compared with layered matching:
 *   EXACT    - identical JSON
 *   TOLERANT - equal after normalisation (number tolerance, case/whitespace, list order, numeric strings,
 *              a one-element list vs a scalar)
 *   MISMATCH - anything else
 * Failures are bucketed (wrong answer / runtime error / rule violation / missing) so the report says
 * not only how often the prompt fails but why. Optional repeated runs give a reproducibility score.
 */
public class Evaluate {

    public enum Match { EXACT, TOLERANT, MISMATCH }

    public enum Outcome { PASS_EXACT, PASS_TOLERANT, WRONG_ANSWER, RUNTIME_ERROR, RULE_VIOLATION, MISSING }

    public record QuestionResult(String id, String question, Outcome outcome, JsonElement expected,
                                 JsonElement actual, String error, Double consistency) {
        public boolean passed() { return outcome == Outcome.PASS_EXACT || outcome == Outcome.PASS_TOLERANT; }
    }

    public record Report(List<QuestionResult> results) {
        public int total() { return results.size(); }
        public long passed() { return results.stream().filter(QuestionResult::passed).count(); }
        public double accuracy() { return total() == 0 ? 0 : (double) passed() / total(); }

        public Map<Outcome, Integer> breakdown() {
            Map<Outcome, Integer> m = new EnumMap<>(Outcome.class);
            for (Outcome o : Outcome.values()) m.put(o, 0);
            results.forEach(r -> m.merge(r.outcome(), 1, Integer::sum));
            return m;
        }

        /** Mean consistency across questions that were run more than once, or null if none were. */
        public Double consistency() {
            return results.stream().map(QuestionResult::consistency).filter(c -> c != null)
                    .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
        }
    }

    static final double ABS_TOL = 0.01;
    static final double REL_TOL = 1e-4;

    // ---------------------------------------------------------------- matching

    public static Match compare(JsonElement expected, JsonElement actual) {
        expected = expected == null ? JsonNull.INSTANCE : expected;
        actual = actual == null ? JsonNull.INSTANCE : actual;
        if (expected.equals(actual)) return Match.EXACT;
        return equivalent(expected, actual) ? Match.TOLERANT : Match.MISMATCH;
    }

    static boolean equivalent(JsonElement e, JsonElement a) {
        if (e.isJsonNull() || a.isJsonNull()) return e.isJsonNull() && a.isJsonNull();

        // ["Beverages"] vs "Beverages" (and vice versa)
        if (e.isJsonArray() && !a.isJsonArray() && e.getAsJsonArray().size() == 1) {
            return equivalent(e.getAsJsonArray().get(0), a);
        }
        if (a.isJsonArray() && !e.isJsonArray() && a.getAsJsonArray().size() == 1) {
            return equivalent(e, a.getAsJsonArray().get(0));
        }

        if (e.isJsonPrimitive() && a.isJsonPrimitive()) {
            Double de = asNumber(e.getAsJsonPrimitive()), da = asNumber(a.getAsJsonPrimitive());
            if (de != null && da != null) return numbersClose(de, da);
            return normalizeText(e.getAsString()).equals(normalizeText(a.getAsString()));
        }

        if (e.isJsonArray() && a.isJsonArray()) {
            JsonArray ea = e.getAsJsonArray(), aa = a.getAsJsonArray();
            if (ea.size() != aa.size()) return false;
            boolean ordered = true;
            for (int i = 0; i < ea.size() && ordered; i++) ordered = equivalent(ea.get(i), aa.get(i));
            return ordered || unorderedEquivalent(ea, aa);
        }

        if (e.isJsonObject() && a.isJsonObject()) {
            Map<String, JsonElement> em = normalizedKeys(e.getAsJsonObject());
            Map<String, JsonElement> am = normalizedKeys(a.getAsJsonObject());
            if (!em.keySet().equals(am.keySet())) return false;
            for (String k : em.keySet()) if (!equivalent(em.get(k), am.get(k))) return false;
            return true;
        }
        return false;
    }

    /** Greedy multiset match; fine for the small result lists these questions produce. */
    private static boolean unorderedEquivalent(JsonArray ea, JsonArray aa) {
        boolean[] used = new boolean[aa.size()];
        outer:
        for (JsonElement x : ea) {
            for (int j = 0; j < aa.size(); j++) {
                if (!used[j] && equivalent(x, aa.get(j))) { used[j] = true; continue outer; }
            }
            return false;
        }
        return true;
    }

    private static Map<String, JsonElement> normalizedKeys(JsonObject o) {
        Map<String, JsonElement> m = new HashMap<>();
        o.entrySet().forEach(en -> m.put(normalizeText(en.getKey()), en.getValue()));
        return m;
    }

    static Double asNumber(JsonPrimitive p) {
        if (p.isNumber()) return p.getAsDouble();
        if (p.isString()) {
            String s = p.getAsString().trim().replace(",", "").replace("$", "");
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    static boolean numbersClose(double x, double y) {
        double diff = Math.abs(x - y);
        return diff <= ABS_TOL || diff <= REL_TOL * Math.max(Math.abs(x), Math.abs(y));
    }

    static String normalizeText(String s) {
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- classification

    public static Outcome classify(JsonElement expected, JsonObject userEntry) {
        if (userEntry == null) return Outcome.MISSING;
        boolean ok = userEntry.has("ok") ? userEntry.get("ok").getAsBoolean() : userEntry.has("result");
        if (!ok) {
            String err = str(userEntry, "error");
            return isRuleViolation(err) ? Outcome.RULE_VIOLATION : Outcome.RUNTIME_ERROR;
        }
        return switch (compare(expected, userEntry.get("result"))) {
            case EXACT -> Outcome.PASS_EXACT;
            case TOLERANT -> Outcome.PASS_TOLERANT;
            case MISMATCH -> Outcome.WRONG_ANSWER;
        };
    }

    static boolean isRuleViolation(String error) {
        return error != null && (error.startsWith("Violation") || error.startsWith("StaticCheck"));
    }

    /** Fraction of repeated runs that agree (tolerantly) with the most common answer. */
    public static Double consistency(JsonArray runs) {
        if (runs == null || runs.size() < 2) return null;
        List<JsonElement> answers = new ArrayList<>();
        for (JsonElement r : runs) {
            JsonObject o = r.getAsJsonObject();
            boolean ok = o.has("ok") && o.get("ok").getAsBoolean();
            answers.add(ok ? o.get("result") : new JsonPrimitive("__error__"));
        }
        int best = 0;
        for (JsonElement x : answers) {
            int n = 0;
            for (JsonElement y : answers) if (compare(x, y) != Match.MISMATCH) n++;
            best = Math.max(best, n);
        }
        return (double) best / answers.size();
    }

    // ---------------------------------------------------------------- evaluation

    public static Report evaluate(JsonArray groundTruth, JsonArray userOutputs, ScriptRunner runner) {
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (JsonElement e : userOutputs) {
            JsonObject o = e.getAsJsonObject();
            byId.put(o.get("id").getAsString(), o);
        }
        List<QuestionResult> results = new ArrayList<>();
        for (JsonElement e : groundTruth) {
            JsonObject q = e.getAsJsonObject();
            String id = q.get("id").getAsString();
            JsonElement expected = q.has("expected") ? q.get("expected") : referenceAnswer(q, runner);
            JsonObject user = byId.get(id);
            Outcome outcome = classify(expected, user);
            results.add(new QuestionResult(id, str(q, "question"), outcome, expected,
                    user == null ? null : user.get("result"), user == null ? "no output" : str(user, "error"),
                    user != null && user.has("runs") ? consistency(user.getAsJsonArray("runs")) : null));
        }
        return new Report(results);
    }

    private static JsonElement referenceAnswer(JsonObject q, ScriptRunner runner) {
        ScriptRunner.RunResult r = runner.run(q.get("script").getAsString());
        if (!r.ok()) throw new IllegalStateException("Reference script " + q.get("id") + " failed: " + r.error());
        return r.result();
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ---------------------------------------------------------------- reporting

    public static String format(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NorthWind script-generation evaluation ===\n");
        sb.append(String.format("Accuracy: %d/%d (%.1f%%)%n", report.passed(), report.total(), 100 * report.accuracy()));
        report.breakdown().forEach((k, v) -> sb.append(String.format("  %-15s %d%n", k, v)));
        Double c = report.consistency();
        if (c != null) sb.append(String.format("Reproducibility (agreement across runs): %.1f%%%n", 100 * c));
        sb.append("\nPer question:\n");
        for (QuestionResult r : report.results()) {
            sb.append(String.format("  [%s] %-14s %s%n", r.passed() ? "PASS" : "FAIL", r.outcome(), r.id()));
            if (!r.passed()) {
                sb.append("      Q: ").append(r.question()).append('\n');
                sb.append("      expected: ").append(truncate(String.valueOf(r.expected()))).append('\n');
                sb.append("      got:      ").append(truncate(r.error() != null ? r.error() : String.valueOf(r.actual())))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String truncate(String s) {
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    public static JsonObject toJson(Report report) {
        Gson gson = new Gson();
        JsonObject o = new JsonObject();
        o.addProperty("total", report.total());
        o.addProperty("passed", report.passed());
        o.addProperty("accuracy", report.accuracy());
        o.add("breakdown", gson.toJsonTree(report.breakdown()));
        if (report.consistency() != null) o.addProperty("consistency", report.consistency());
        JsonArray arr = new JsonArray();
        for (QuestionResult r : report.results()) {
            JsonObject q = new JsonObject();
            q.addProperty("id", r.id());
            q.addProperty("outcome", r.outcome().name());
            q.add("expected", r.expected());
            q.add("actual", r.actual());
            q.addProperty("error", r.error());
            arr.add(q);
        }
        o.add("questions", arr);
        return o;
    }

    /** Usage: [user_outputs.json] [ground_truth.json] */
    public static void main(String[] args) throws IOException {
        Path userPath = Path.of(args.length > 0 ? args[0] : "user_outputs.json");
        Path gtPath = Path.of(args.length > 1 ? args[1] : "src/test/resources/ground_truth.json");
        JsonArray gt = JsonParser.parseString(Files.readString(gtPath)).getAsJsonArray();
        JsonArray user = JsonParser.parseString(Files.readString(userPath)).getAsJsonArray();

        Report report = evaluate(gt, user, new ScriptRunner());
        System.out.print(format(report));
        Path out = Path.of("evaluation_report.json");
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(toJson(report)));
        System.out.println("\nWrote " + out);
    }
}

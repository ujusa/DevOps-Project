import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EvaluateTest {

    private static JsonElement j(String json) { return JsonParser.parseString(json); }

    private static JsonObject entry(String id, boolean ok, String resultJson, String error) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("ok", ok);
        o.add("result", resultJson == null ? null : j(resultJson));
        o.addProperty("error", error);
        return o;
    }

    @Test void identicalValuesAreExact() {
        assertEquals(Evaluate.Match.EXACT, Evaluate.compare(j("[1,2,3]"), j("[1,2,3]")));
        assertEquals(Evaluate.Match.EXACT, Evaluate.compare(j("\"Chai\""), j("\"Chai\"")));
    }

    @Test void numbersWithinToleranceMatch() {
        assertEquals(Evaluate.Match.TOLERANT, Evaluate.compare(j("358640.25"), j("358640.2512")));
        assertNotEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("20"), j("20.0")));
        assertEquals(Evaluate.Match.TOLERANT, Evaluate.compare(j("20"), j("\"20\"")));
        assertEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("20"), j("21")));
    }

    @Test void textIgnoresCaseAndWhitespace() {
        assertEquals(Evaluate.Match.TOLERANT, Evaluate.compare(j("\"Janet Leverling\""), j("\"  janet   leverling \"")));
        assertEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("\"Janet\""), j("\"Nancy\"")));
    }

    @Test void listsMatchRegardlessOfOrderButNotSize() {
        assertEquals(Evaluate.Match.TOLERANT, Evaluate.compare(j("[\"UK\",\"USA\"]"), j("[\"USA\",\"uk\"]")));
        assertEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("[\"UK\",\"USA\"]"), j("[\"USA\"]")));
        assertEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("[\"UK\",\"UK\"]"), j("[\"UK\",\"USA\"]")));
    }

    @Test void singletonListEqualsScalar() {
        assertEquals(Evaluate.Match.TOLERANT, Evaluate.compare(j("\"Beverages\""), j("[\"Beverages\"]")));
    }

    @Test void dictsCompareKeyByKey() {
        assertEquals(Evaluate.Match.TOLERANT,
                Evaluate.compare(j("{\"Speedy Express\":128,\"United Package\":136}"),
                        j("{\"united package\":136.0,\"Speedy Express\":128}")));
        assertEquals(Evaluate.Match.MISMATCH,
                Evaluate.compare(j("{\"A\":1,\"B\":2}"), j("{\"A\":1}")));
    }

    @Test void nullsOnlyMatchNulls() {
        assertEquals(Evaluate.Match.EXACT, Evaluate.compare(null, null));
        assertEquals(Evaluate.Match.MISMATCH, Evaluate.compare(j("0"), null));
    }

    @Test void classifiesFailureKinds() {
        assertEquals(Evaluate.Outcome.MISSING, Evaluate.classify(j("1"), null));
        assertEquals(Evaluate.Outcome.RUNTIME_ERROR,
                Evaluate.classify(j("1"), entry("q", false, null, "KeyError: 'Name'")));
        assertEquals(Evaluate.Outcome.RULE_VIOLATION,
                Evaluate.classify(j("1"), entry("q", false, null, "Violation: raw SQL is not allowed")));
        assertEquals(Evaluate.Outcome.WRONG_ANSWER, Evaluate.classify(j("1"), entry("q", true, "2", null)));
        assertEquals(Evaluate.Outcome.PASS_EXACT, Evaluate.classify(j("1"), entry("q", true, "1", null)));
    }

    @Test void consistencyIsShareAgreeingWithMajority() {
        JsonArray runs = j("[{\"ok\":true,\"result\":5},{\"ok\":true,\"result\":5.0},{\"ok\":false,\"error\":\"x\"},"
                + "{\"ok\":true,\"result\":6}]").getAsJsonArray();
        assertEquals(0.5, Evaluate.consistency(runs), 1e-9);
        assertNull(Evaluate.consistency(j("[{\"ok\":true,\"result\":1}]").getAsJsonArray()));
    }

    @Test void referenceScriptsScorePerfectlyAgainstThemselves() throws Exception {
        JsonArray gt = JsonParser.parseString(
                Files.readString(Path.of("src/test/resources/ground_truth.json"))).getAsJsonArray();
        ScriptRunner runner = new ScriptRunner();
        JsonArray user = new JsonArray();
        for (JsonElement e : gt) {
            JsonObject q = e.getAsJsonObject();
            ScriptRunner.RunResult r = runner.run(q.get("script").getAsString());
            assertTrue(r.ok(), q.get("id") + ": " + r.error());
            JsonObject o = new JsonObject();
            o.addProperty("id", q.get("id").getAsString());
            o.addProperty("ok", true);
            o.add("result", r.result());
            user.add(o);
        }
        Evaluate.Report report = Evaluate.evaluate(gt, user, runner);
        assertEquals(gt.size(), report.passed(), Evaluate.format(report));
        assertEquals(1.0, report.accuracy(), 1e-9);
    }

    @Test void reportCountsEveryOutcome() {
        JsonArray gt = j("[{\"id\":\"a\",\"question\":\"?\",\"expected\":1},"
                + "{\"id\":\"b\",\"question\":\"?\",\"expected\":[\"x\",\"y\"]},"
                + "{\"id\":\"c\",\"question\":\"?\",\"expected\":3},"
                + "{\"id\":\"d\",\"question\":\"?\",\"expected\":4}]").getAsJsonArray();
        JsonArray user = new JsonArray();
        user.add(entry("a", true, "1", null));
        user.add(entry("b", true, "[\"Y\",\"x\"]", null));
        user.add(entry("c", false, null, "Violation: import of 'sqlite3' is not allowed"));
        Evaluate.Report report = Evaluate.evaluate(gt, user, new ScriptRunner());
        assertEquals(2, report.passed());
        assertEquals(1, report.breakdown().get(Evaluate.Outcome.PASS_EXACT));
        assertEquals(1, report.breakdown().get(Evaluate.Outcome.PASS_TOLERANT));
        assertEquals(1, report.breakdown().get(Evaluate.Outcome.RULE_VIOLATION));
        assertEquals(1, report.breakdown().get(Evaluate.Outcome.MISSING));
        assertTrue(Evaluate.format(report).contains("Accuracy: 2/4"));
    }
}

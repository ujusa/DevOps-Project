import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScriptGeneratorTest {

    /** Replays canned replies and records every conversation it was shown. */
    static class FakeLlm implements ScriptGenerator.LlmClient {
        final Deque<String> replies;
        final List<List<Map<String, String>>> seen = new ArrayList<>();
        FakeLlm(String... replies) { this.replies = new ArrayDeque<>(List.of(replies)); }
        @Override public String chat(List<Map<String, String>> messages) {
            seen.add(List.copyOf(messages));
            return replies.isEmpty() ? "result = None" : replies.poll();
        }
    }

    private final ScriptRunner runner = new ScriptRunner();

    @Test void extractsCodeFromFencedReply() {
        String reply = "Here you go:\n```python\nresult = len(nw_api.get_customers())\n```\nHope it helps";
        assertEquals("result = len(nw_api.get_customers())\n", ScriptGenerator.extractCode(reply));
        assertEquals("result = 1\n", ScriptGenerator.extractCode("result = 1"));
        assertEquals("", ScriptGenerator.extractCode(null));
    }

    @Test void staticChecksCatchRuleViolations() {
        assertTrue(ScriptGenerator.staticProblems("result = len(nw_api.get_customers())\n").isEmpty());
        assertFalse(ScriptGenerator.staticProblems("import sqlite3\nresult = 1").isEmpty());
        assertFalse(ScriptGenerator.staticProblems("q = 'SELECT * FROM Customers'\nresult = q").isEmpty());
        assertFalse(ScriptGenerator.staticProblems("print(len(nw_api.get_customers()))").isEmpty());
        assertFalse(ScriptGenerator.staticProblems("").isEmpty());
    }

    @Test void systemPromptContainsLiveApiSurfaceAndRules() {
        String prompt = new ScriptGenerator(new FakeLlm(), runner).systemPrompt();
        assertTrue(prompt.contains("nw_api.get_orders(customer_id=None"), prompt);
        assertTrue(prompt.contains("Never write SQL"));
        assertTrue(prompt.contains("`result`"));
    }

    @Test void succeedsFirstTimeOnGoodScript() {
        FakeLlm llm = new FakeLlm("```python\nresult = len(nw_api.get_customers(country='Germany'))\n```");
        ScriptGenerator.Generation g = new ScriptGenerator(llm, runner).generate("How many German customers?");
        assertTrue(g.ok(), g.error());
        assertEquals(4, g.result().getAsInt());
        assertEquals(1, g.attempts());
    }

    @Test void repairsAfterSqlAndRuntimeErrors() {
        FakeLlm llm = new FakeLlm(
                "import sqlite3\nresult = sqlite3.connect('northwind.db').execute('SELECT COUNT(*) FROM Customers').fetchone()[0]",
                "result = len(nw_api.get_customer())",           // typo -> AttributeError at runtime
                "result = len(nw_api.get_customers())");
        ScriptGenerator.Generation g = new ScriptGenerator(llm, runner).generate("How many customers?");
        assertTrue(g.ok(), g.error());
        assertEquals(20, g.result().getAsInt());
        assertEquals(3, g.attempts());
        // the error from each failed attempt was fed back to the model
        String lastFeedback = llm.seen.get(2).get(llm.seen.get(2).size() - 1).get("content");
        assertTrue(lastFeedback.contains("AttributeError"), lastFeedback);
        assertTrue(llm.seen.get(1).get(llm.seen.get(1).size() - 1).get("content").contains("SQL"));
    }

    @Test void runnerBlocksSqlEvenIfStaticCheckIsBypassed() {
        ScriptRunner.RunResult r = runner.run("import sqlite3\nresult = 1\n");
        assertFalse(r.ok());
        assertTrue(r.error().startsWith("Violation"), r.error());
    }

    @Test void givesUpAfterMaxAttempts() {
        FakeLlm llm = new FakeLlm("result = 1/0", "result = 1/0", "result = 1/0", "result = 1/0");
        ScriptGenerator.Generation g = new ScriptGenerator(llm, runner, 2, false).generate("?");
        assertFalse(g.ok());
        assertTrue(g.error().contains("ZeroDivisionError"), g.error());
        assertEquals(2, llm.seen.size());
    }

    @Test void llmFailureIsReportedNotThrown() {
        ScriptGenerator.LlmClient broken = m -> { throw new IllegalStateException("HTTP 401"); };
        ScriptGenerator.Generation g = new ScriptGenerator(broken, runner).generate("?");
        assertFalse(g.ok());
        assertTrue(g.error().contains("HTTP 401"));
    }
}

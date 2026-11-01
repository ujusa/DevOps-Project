import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a natural-language question into a Python script that uses only nw_api.
 *
 * Reliability comes from four layers:
 *   1. A strict prompt: exact API surface (generated from nw_api itself), hard rules, an output contract
 *      (assign to {@code result}) and worked examples covering counts, lists, revenue and joins.
 *   2. Deterministic decoding: temperature 0 and a fixed seed.
 *   3. Static checks before execution (fences stripped, no SQL / sqlite3 / disallowed imports, has {@code result}).
 *   4. A self-repair loop: on a static or runtime failure the error is fed back to the model, up to N attempts.
 */
public class ScriptGenerator {

    /** Minimal chat abstraction so the generator can be tested without network access. */
    public interface LlmClient {
        String chat(List<Map<String, String>> messages);
    }

    /** One question's final outcome, written to user_outputs.json. */
    public record Generation(String script, boolean ok, JsonElement result, String error, int attempts) { }

    /** The original, underperforming style of prompt, kept for A/B comparison (--baseline). */
    static final String BASELINE_SYSTEM_PROMPT =
            "You are a helpful assistant. Write a Python script using the nw_api module to answer the question.";

    static final String RULES = """
            You write Python 3 scripts that answer business questions about the NorthWind trading database.

            HARD RULES
            1. Read data ONLY through the nw_api functions listed below. `nw_api` is already imported.
            2. Never write SQL, never import sqlite3, os, sys, subprocess or pandas, never open files.
               Allowed imports: json, math, statistics, collections, datetime, itertools, functools, operator, re.
            3. Assign the final answer to a variable named `result`. Do not print anything.
            4. `result` must be plain JSON-compatible data:
               - a count or amount        -> int or float (round money/averages to 2 decimals)
               - a single name/value      -> str
               - several names/values     -> list (sorted alphabetically unless the question asks for a ranking,
                                             in which case keep rank order)
               - a breakdown "X per Y"    -> dict {Y: X}
               Use human-readable names (CompanyName, ProductName, "First Last") rather than IDs unless IDs are asked for.
            5. Revenue of an order line = UnitPrice * Quantity * (1 - Discount).
               "In <year>" means OrderDate between '<year>-01-01' and '<year>-12-31' inclusive.
            6. Reply with ONLY the Python code. No markdown fences, no explanation.

            AVAILABLE API (exact signatures)
            %s

            EXAMPLES
            Question: How many suppliers are in the USA?
            result = len(nw_api.get_suppliers(country='USA'))

            Question: List the product names in the Seafood category.
            cat = next(c['CategoryID'] for c in nw_api.get_categories() if c['CategoryName'] == 'Seafood')
            result = sorted(p['ProductName'] for p in nw_api.get_products(category_id=cat))

            Question: What was the revenue per country in 1998?
            from collections import defaultdict
            orders = {o['OrderID']: o['ShipCountry'] for o in nw_api.get_orders(start_date='1998-01-01', end_date='1998-12-31')}
            rev = defaultdict(float)
            for d in nw_api.get_order_details():
                if d['OrderID'] in orders:
                    rev[orders[d['OrderID']]] += d['UnitPrice'] * d['Quantity'] * (1 - d['Discount'])
            result = {k: round(v, 2) for k, v in sorted(rev.items())}
            """;

    private static final Pattern FENCE = Pattern.compile("```(?:python|py)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern SQL = Pattern.compile(
            "\\b(SELECT\\s+.+?\\s+FROM|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BANNED_IMPORT = Pattern.compile(
            "^\\s*(?:import|from)\\s+(sqlite3|os|sys|subprocess|pandas|shutil|socket|requests)\\b", Pattern.MULTILINE);
    private static final Pattern ASSIGNS_RESULT = Pattern.compile("^\\s*result\\s*=", Pattern.MULTILINE);

    private final LlmClient llm;
    private final ScriptRunner runner;
    private final int maxAttempts;
    private final boolean baseline;
    private String systemPrompt;

    public ScriptGenerator(LlmClient llm, ScriptRunner runner) {
        this(llm, runner, 3, false);
    }

    public ScriptGenerator(LlmClient llm, ScriptRunner runner, int maxAttempts, boolean baseline) {
        this.llm = llm;
        this.runner = runner;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseline = baseline;
    }

    public String systemPrompt() {
        if (systemPrompt == null) {
            systemPrompt = baseline ? BASELINE_SYSTEM_PROMPT : RULES.formatted(runner.describeApi());
        }
        return systemPrompt;
    }

    static String userPrompt(String question) {
        return "Question: " + question.strip();
    }

    /** Pull the code out of a model reply: prefer a fenced block, otherwise the whole reply. */
    public static String extractCode(String reply) {
        if (reply == null) return "";
        Matcher m = FENCE.matcher(reply);
        String code = m.find() ? m.group(1) : reply.replace("```", "");
        return code.strip() + "\n";
    }

    /** Cheap checks that catch rule violations before we spend a subprocess on them. */
    public static List<String> staticProblems(String code) {
        List<String> problems = new ArrayList<>();
        if (code.isBlank()) problems.add("The reply contained no code.");
        if (SQL.matcher(code).find()) problems.add("The script contains raw SQL. Use nw_api functions only.");
        Matcher imp = BANNED_IMPORT.matcher(code);
        if (imp.find()) problems.add("Importing '" + imp.group(1) + "' is not allowed. Use nw_api only.");
        if (!ASSIGNS_RESULT.matcher(code).find()) problems.add("The script must assign the answer to `result`.");
        return problems;
    }

    public Generation generate(String question) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        messages.add(Map.of("role", "user", "content", userPrompt(question)));

        String code = "";
        String error = "no attempt made";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String reply;
            try {
                reply = llm.chat(messages);
            } catch (RuntimeException e) {
                return new Generation(code, false, null, "LlmError: " + e.getMessage(), attempt);
            }
            code = extractCode(reply);
            messages.add(Map.of("role", "assistant", "content", code));

            List<String> problems = baseline ? List.of() : staticProblems(code);
            if (problems.isEmpty()) {
                ScriptRunner.RunResult r = runner.run(code);
                if (r.ok()) return new Generation(code, true, r.result(), null, attempt);
                error = r.error();
            } else {
                error = "StaticCheck: " + String.join(" ", problems);
            }
            if (baseline) break; // the baseline gets no repair loop
            messages.add(Map.of("role", "user", "content",
                    "That script failed: " + error + "\nFix it and reply with ONLY the corrected Python code."));
        }
        return new Generation(code, false, null, error, baseline ? 1 : maxAttempts);
    }

    // ---------------------------------------------------------------- OpenAI client

    /** OpenAI Chat Completions client using only the JDK. Temperature 0 + fixed seed for reproducibility. */
    public static class OpenAiClient implements LlmClient {
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        private final String apiKey;
        private final String model;
        private final String baseUrl;

        public OpenAiClient() {
            this(System.getenv("OPENAI_API_KEY"),
                    System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini"),
                    System.getenv().getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"));
        }

        public OpenAiClient(String apiKey, String model, String baseUrl) {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is not set");
            this.apiKey = apiKey;
            this.model = model;
            this.baseUrl = baseUrl.replaceAll("/+$", "");
        }

        @Override
        public String chat(List<Map<String, String>> messages) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", 0);
            body.addProperty("seed", 7);
            JsonArray msgs = new JsonArray();
            for (Map<String, String> m : messages) {
                JsonObject o = new JsonObject();
                o.addProperty("role", m.get("role"));
                o.addProperty("content", m.get("content"));
                msgs.add(o);
            }
            body.add("messages", msgs);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            for (int i = 0; ; i++) {
                try {
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                        if (i < 3) { Thread.sleep(1000L << i); continue; }
                    }
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    return JsonParser.parseString(resp.body()).getAsJsonObject()
                            .getAsJsonArray("choices").get(0).getAsJsonObject()
                            .getAsJsonObject("message").get("content").getAsString();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (i >= 3) throw new IllegalStateException(e.getMessage(), e);
                }
            }
        }
    }

    // ---------------------------------------------------------------- CLI

    /** Usage: [user_outputs.json] [ground_truth.json] [--runs N] [--baseline] */
    public static void main(String[] args) throws IOException {
        List<String> positional = new ArrayList<>();
        int runs = 1;
        boolean baseline = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--baseline" -> baseline = true;
                default -> positional.add(args[i]);
            }
        }
        Path outPath = Path.of(positional.size() > 0 ? positional.get(0) : "user_outputs.json");
        Path gtPath = Path.of(positional.size() > 1 ? positional.get(1) : "src/test/resources/ground_truth.json");

        ScriptGenerator gen = new ScriptGenerator(new OpenAiClient(), new ScriptRunner(), 3, baseline);
        JsonArray questions = JsonParser.parseString(Files.readString(gtPath)).getAsJsonArray();
        JsonArray out = new JsonArray();
        for (JsonElement e : questions) {
            JsonObject q = e.getAsJsonObject();
            String question = q.get("question").getAsString();
            JsonArray runResults = new JsonArray();
            Generation first = null;
            for (int r = 0; r < runs; r++) {
                Generation g = gen.generate(question);
                if (first == null) first = g;
                JsonObject rr = new JsonObject();
                rr.addProperty("ok", g.ok());
                rr.add("result", g.result());
                rr.addProperty("error", g.error());
                runResults.add(rr);
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", q.get("id").getAsString());
            o.addProperty("question", question);
            o.addProperty("script", first.script());
            o.addProperty("ok", first.ok());
            o.add("result", first.result());
            o.addProperty("error", first.error());
            o.addProperty("attempts", first.attempts());
            if (runs > 1) o.add("runs", runResults);
            out.add(o);
            System.out.printf("%-4s %-6s attempts=%d %s%n", q.get("id").getAsString(), first.ok() ? "OK" : "FAIL",
                    first.attempts(), first.ok() ? first.result() : first.error());
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        Files.writeString(outPath, gson.toJson(out));
        System.out.println("Wrote " + outPath);
    }
}

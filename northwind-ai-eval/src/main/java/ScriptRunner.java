import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs Python scripts through python/runner.py in a subprocess with a hard timeout. */
public class ScriptRunner {

    /** Outcome of one script execution. {@code result} is null when {@code ok} is false. */
    public record RunResult(boolean ok, JsonElement result, String error) {
        static RunResult failure(String error) { return new RunResult(false, null, error); }
    }

    private final String python;
    private final Path runnerPath;
    private final long timeoutSeconds;

    public ScriptRunner() {
        this(System.getenv().getOrDefault("NW_PYTHON", "python3"), Path.of("python", "runner.py"), 30);
    }

    public ScriptRunner(String python, Path runnerPath, long timeoutSeconds) {
        this.python = python;
        this.runnerPath = runnerPath;
        this.timeoutSeconds = timeoutSeconds;
    }

    public RunResult run(String script) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("nw_script_", ".py");
            Files.writeString(tmp, script, StandardCharsets.UTF_8);
            String out = exec(List.of(python, runnerPath.toString(), tmp.toString()));
            return parse(out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return RunResult.failure("RunnerError: " + e.getMessage());
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    /** The nw_api function signatures + docs, straight from the module, so prompts never drift from the API. */
    public String describeApi() {
        try {
            return exec(List.of(python, runnerPath.toString(), "--describe")).trim();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Could not describe nw_api: " + e.getMessage(), e);
        }
    }

    private String exec(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("timed out after " + timeoutSeconds + "s");
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    static RunResult parse(String output) {
        String[] lines = output.strip().split("\\R");
        String last = lines.length == 0 ? "" : lines[lines.length - 1];
        try {
            JsonObject o = JsonParser.parseString(last).getAsJsonObject();
            if (o.get("ok").getAsBoolean()) return new RunResult(true, o.get("result"), null);
            return RunResult.failure(o.get("error").getAsString());
        } catch (RuntimeException e) {
            return RunResult.failure("RunnerError: unparseable output: " + output.strip());
        }
    }
}

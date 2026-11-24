package fi.alavesa.scp079.gemini;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Executes a function call of the model; completes with the text handed back to it. Never fails. */
@FunctionalInterface
public interface ToolRunner {
    CompletableFuture<String> run(String name, Map<String, String> arguments);
}

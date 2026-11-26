package fi.alavesa.scp079.gemini;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.alavesa.scp079.config.SecretStore;
import fi.alavesa.scp079.config.Settings;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The Gemini REST API ({@code POST <endpoint>/models/<model>:generateContent}), fully asynchronous.
 * <p>
 * The returned future completes on an HTTP worker thread - never on the server thread - and never
 * exceptionally: every failure becomes a {@link GeminiResult} with an {@link GeminiResult.Error}. Callers
 * must hop back to the main thread before touching Bukkit.
 * <p>
 * The API key travels only in the {@code x-goog-api-key} header: it is not part of the URL and is scrubbed
 * from any error text.
 */
public final class GeminiClient {

    private static final Gson GSON = new Gson();

    private final Supplier<Settings> settings;
    private final Supplier<String> safety;
    private final SecretStore secrets;
    private final ExecutorService executor;
    private final HttpClient http;
    private final AtomicInteger inFlight = new AtomicInteger();

    public GeminiClient(Supplier<Settings> settings, SecretStore secrets) {
        this(settings, secrets, () -> DEFAULT_SAFETY);
    }

    /** @param safety Google's block threshold: BLOCK_LOW_AND_ABOVE (strictest), BLOCK_MEDIUM_AND_ABOVE, BLOCK_ONLY_HIGH */
    public GeminiClient(Supplier<Settings> settings, SecretStore secrets, Supplier<String> safety) {
        this.safety = safety;
        this.settings = settings;
        this.secrets = secrets;
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "SCP079-Gemini");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();
    }

    /** The most function-call rounds one answer may take (call -> result -> call -> result -> text). */
    static final int MAX_TOOL_ROUNDS = 4;
    static final String DEFAULT_SAFETY = "BLOCK_LOW_AND_ABOVE";
    private static final String[] CATEGORIES = {"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"};

    /**
     * @param systemPrompt the persona (systemInstruction); may be empty
     * @param turns        the conversation so far, oldest first, starting with and ending on a user turn
     */
    public CompletableFuture<GeminiResult> generate(String systemPrompt, List<Turn> turns) {
        return generate(systemPrompt, turns, List.of(), null);
    }

    /**
     * Same, with function calling: when the model answers with function calls they are run through
     * {@code runner} (at most {@link #MAX_TOOL_ROUNDS} rounds) and their results sent back until it writes text.
     * The whole model turn is echoed back unchanged, which keeps Gemini 3's thought signatures intact.
     */
    public CompletableFuture<GeminiResult> generate(String systemPrompt, List<Turn> turns, List<ToolSpec> tools, ToolRunner runner) {
        Settings.Gemini cfg = settings.get().gemini();
        String key = secrets.key();
        if (key.isEmpty()) return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NO_KEY, 0, "no API key is set (/scp079 setkey)", 0));
        if (inFlight.incrementAndGet() > cfg.maxConcurrent()) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.BUSY, 0,
                "gemini.max-concurrent-requests (" + cfg.maxConcurrent() + ") requests are already in flight", 0));
        }

        long started = System.nanoTime();
        JsonObject body = body(cfg, systemPrompt, turns);
        body.add("safetySettings", safetySettings(safety.get()));
        if (tools != null && !tools.isEmpty() && runner != null) body.add("tools", declarations(tools));
        CompletableFuture<GeminiResult> result;
        try {
            result = step(cfg, key, body, runner, 0, started);
        } catch (RuntimeException e) {
            result = CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NETWORK, 0, secrets.scrub(String.valueOf(e.getMessage())), 0));
        }
        return result.whenComplete((r, e) -> inFlight.decrementAndGet());
    }

    private CompletableFuture<GeminiResult> step(Settings.Gemini cfg, String key, JsonObject body, ToolRunner runner, int round, long started) {
        HttpRequest request;
        try {
            // the model id is a path segment; encode it so that a typo in the config cannot reshape the URL
            String model = URLEncoder.encode(cfg.model(), StandardCharsets.UTF_8).replace("+", "%20");
            request = HttpRequest.newBuilder(URI.create(cfg.endpoint() + "/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NETWORK, 0,
                "gemini.endpoint is not a valid URL: " + secrets.scrub(String.valueOf(e.getMessage())), 0));
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .handle((response, error) -> {
                long millis = (System.nanoTime() - started) / 1_000_000;
                try {
                    if (error != null) return CompletableFuture.completedFuture(failure(error, cfg, millis));
                    JsonObject modelTurn = response.statusCode() / 100 == 2 && runner != null && round < MAX_TOOL_ROUNDS ? functionCallTurn(response.body()) : null;
                    if (modelTurn == null) return CompletableFuture.completedFuture(interpret(response.statusCode(), response.body(), millis));
                    return answerCalls(cfg, key, body, modelTurn, runner, round, started);
                } catch (RuntimeException e) {
                    return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NETWORK, 0, "unexpected response: " + e, millis));
                }
            }).thenCompose(f -> f);
    }

    /** Runs the calls of {@code modelTurn}, appends turn and results to the request and asks again. */
    private CompletableFuture<GeminiResult> answerCalls(Settings.Gemini cfg, String key, JsonObject body, JsonObject modelTurn, ToolRunner runner, int round, long started) {
        List<CompletableFuture<JsonObject>> parts = new java.util.ArrayList<>();
        for (JsonElement p : modelTurn.getAsJsonArray("parts")) {
            if (!p.isJsonObject() || !p.getAsJsonObject().has("functionCall")) continue;
            JsonObject call = p.getAsJsonObject().getAsJsonObject("functionCall");
            String name = string(call.get("name"));
            java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
            if (call.has("args") && call.get("args").isJsonObject())
                for (var e : call.getAsJsonObject("args").entrySet()) args.put(e.getKey(), string(e.getValue()));
            CompletableFuture<String> run;
            try { run = runner.run(name, args); } catch (RuntimeException e) { run = CompletableFuture.completedFuture("error: " + e.getMessage()); }
            parts.add(run.exceptionally(e -> "error: " + e.getMessage()).thenApply(text -> {
                JsonObject response = new JsonObject();
                response.addProperty("result", text);
                JsonObject fr = new JsonObject();
                fr.addProperty("name", name);
                fr.add("response", response);
                JsonObject part = new JsonObject();
                part.add("functionResponse", fr);
                return part;
            }));
        }
        return CompletableFuture.allOf(parts.toArray(new CompletableFuture[0])).thenCompose(v -> {
            JsonArray answer = new JsonArray();
            for (CompletableFuture<JsonObject> f : parts) answer.add(f.join());
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.add("parts", answer);
            JsonArray contents = body.getAsJsonArray("contents");
            contents.add(modelTurn);
            contents.add(user);
            return step(cfg, key, body, runner, round + 1, started);
        });
    }

    /** The model turn of the first candidate when it contains function calls, else null. */
    static JsonObject functionCallTurn(String responseBody) {
        try {
            JsonElement parsed = JsonParser.parseString(responseBody == null ? "" : responseBody);
            if (!parsed.isJsonObject()) return null;
            JsonObject json = parsed.getAsJsonObject();
            if (!json.has("candidates") || !json.get("candidates").isJsonArray() || json.getAsJsonArray("candidates").isEmpty()) return null;
            JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
            if (!candidate.has("content") || !candidate.get("content").isJsonObject()) return null;
            JsonObject content = candidate.getAsJsonObject("content");
            if (!content.has("parts") || !content.get("parts").isJsonArray()) return null;
            for (JsonElement p : content.getAsJsonArray("parts"))
                if (p.isJsonObject() && p.getAsJsonObject().has("functionCall")) {
                    if (!content.has("role")) content.addProperty("role", "model");
                    return content;
                }
        } catch (RuntimeException ignored) {
            // not usable: the caller falls back to interpret()
        }
        return null;
    }

    static JsonArray safetySettings(String threshold) {
        String t = threshold == null || !threshold.matches("BLOCK_(LOW_AND_ABOVE|MEDIUM_AND_ABOVE|ONLY_HIGH|NONE)") ? DEFAULT_SAFETY : threshold;
        JsonArray out = new JsonArray();
        for (String category : CATEGORIES) {
            JsonObject o = new JsonObject();
            o.addProperty("category", category);
            o.addProperty("threshold", t);
            out.add(o);
        }
        return out;
    }

    static JsonArray declarations(List<ToolSpec> tools) {
        JsonArray declared = new JsonArray();
        for (ToolSpec tool : tools) {
            JsonObject fn = new JsonObject();
            fn.addProperty("name", tool.name());
            fn.addProperty("description", tool.description());
            if (!tool.parameters().isEmpty()) {
                JsonObject properties = new JsonObject();
                JsonArray required = new JsonArray();
                for (var e : tool.parameters().entrySet()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("type", "STRING");
                    p.addProperty("description", e.getValue());
                    properties.add(e.getKey(), p);
                    required.add(e.getKey());
                }
                JsonObject schema = new JsonObject();
                schema.addProperty("type", "OBJECT");
                schema.add("properties", properties);
                schema.add("required", required);
                fn.add("parameters", schema);
            }
            declared.add(fn);
        }
        JsonObject tool = new JsonObject();
        tool.add("functionDeclarations", declared);
        JsonArray out = new JsonArray();
        out.add(tool);
        return out;
    }

    static JsonObject body(Settings.Gemini cfg, String systemPrompt, List<Turn> turns) {
        JsonObject body = new JsonObject();
        if (systemPrompt != null && !systemPrompt.isBlank()) body.add("systemInstruction", content(null, systemPrompt));
        JsonArray contents = new JsonArray();
        for (Turn turn : turns) contents.add(content(turn.role(), turn.text()));
        body.add("contents", contents);

        JsonObject generation = new JsonObject();
        generation.addProperty("temperature", cfg.temperature());
        generation.addProperty("maxOutputTokens", cfg.maxOutputTokens());
        generation.addProperty("candidateCount", 1);
        if (!cfg.thinkingLevel().isEmpty()) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("thinkingLevel", cfg.thinkingLevel());
            generation.add("thinkingConfig", thinking);
        }
        body.add("generationConfig", generation);
        return body;
    }

    private static JsonObject content(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        if (role != null) content.addProperty("role", role);
        content.add("parts", parts);
        return content;
    }

    private GeminiResult failure(Throwable error, Settings.Gemini cfg, long millis) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof HttpTimeoutException) {
            return GeminiResult.failure(GeminiResult.Error.TIMEOUT, 0, "no answer within " + cfg.timeoutSeconds() + " s (gemini.timeout-seconds)", millis);
        }
        return GeminiResult.failure(GeminiResult.Error.NETWORK, 0,
            cause.getClass().getSimpleName() + ": " + secrets.scrub(String.valueOf(cause.getMessage())), millis);
    }

    /** Package-private for tests: maps an HTTP response to a result. */
    GeminiResult interpret(int status, String body, long millis) {
        JsonObject json = null;
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (parsed != null && parsed.isJsonObject()) json = parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // not JSON (a proxy's HTML error page): handled below by status
        }

        if (status < 200 || status >= 300) {
            String message = "";
            if (json != null && json.has("error") && json.get("error").isJsonObject()) {
                JsonObject error = json.getAsJsonObject("error");
                message = string(error.get("status")) + (error.has("message") ? " - " + string(error.get("message")) : "");
            }
            String detail = secrets.scrub("HTTP " + status + (message.isBlank() ? "" : ": " + shorten(message)));
            GeminiResult.Error kind =
                status == 429 ? GeminiResult.Error.RATE_LIMIT
                : status == 400 || status == 401 || status == 403 || status == 404 ? GeminiResult.Error.BAD_KEY
                : status == 408 || status == 504 ? GeminiResult.Error.TIMEOUT
                : status >= 500 ? GeminiResult.Error.SERVER
                : GeminiResult.Error.NETWORK;
            return GeminiResult.failure(kind, status, detail, millis);
        }
        if (json == null) return GeminiResult.failure(GeminiResult.Error.EMPTY, status, "HTTP " + status + " but the body is not JSON", millis);

        if (json.has("promptFeedback") && json.get("promptFeedback").isJsonObject()) {
            String blockReason = string(json.getAsJsonObject("promptFeedback").get("blockReason"));
            if (!blockReason.isEmpty() && !blockReason.equals("BLOCK_REASON_UNSPECIFIED")) {
                return GeminiResult.failure(GeminiResult.Error.BLOCKED, status, "the prompt was blocked: " + blockReason, millis);
            }
        }
        String finishReason = "";
        StringBuilder text = new StringBuilder();
        if (json.has("candidates") && json.get("candidates").isJsonArray() && !json.getAsJsonArray("candidates").isEmpty()
            && json.getAsJsonArray("candidates").get(0).isJsonObject()) {
            JsonObject candidate = json.getAsJsonArray("candidates").get(0).getAsJsonObject();
            finishReason = string(candidate.get("finishReason"));
            if (candidate.has("content") && candidate.get("content").isJsonObject()) {
                JsonObject content = candidate.getAsJsonObject("content");
                if (content.has("parts") && content.get("parts").isJsonArray()) {
                    for (JsonElement p : content.getAsJsonArray("parts")) {
                        if (!p.isJsonObject()) continue;
                        JsonObject part = p.getAsJsonObject();
                        // "thought" parts are the model's reasoning summary, not the answer
                        if (part.has("thought") && part.get("thought").isJsonPrimitive() && part.get("thought").getAsBoolean()) continue;
                        text.append(string(part.get("text")));
                    }
                }
            }
        }
        if (!text.toString().isBlank()) return GeminiResult.success(text.toString(), status, millis);

        return switch (finishReason) {
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" ->
                GeminiResult.failure(GeminiResult.Error.BLOCKED, status, "the answer was blocked: " + finishReason, millis);
            case "MAX_TOKENS" ->
                GeminiResult.failure(GeminiResult.Error.EMPTY, status,
                    "no text before the token limit (finishReason MAX_TOKENS) - the model spent gemini.max-output-tokens on thinking; raise it or set gemini.thinking-level", millis);
            default ->
                GeminiResult.failure(GeminiResult.Error.EMPTY, status, "the response has no text" + (finishReason.isEmpty() ? "" : " (finishReason " + finishReason + ")"), millis);
        };
    }

    private static String string(JsonElement e) { return e == null || !e.isJsonPrimitive() ? "" : e.getAsString(); }

    private static String shorten(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }

    /** Aborts requests in flight and stops the worker threads. */
    public void close() {
        http.shutdownNow();
        executor.shutdownNow();
    }
}

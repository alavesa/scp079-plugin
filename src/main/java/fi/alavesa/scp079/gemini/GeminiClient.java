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
    private final SecretStore secrets;
    private final ExecutorService executor;
    private final HttpClient http;
    private final AtomicInteger inFlight = new AtomicInteger();

    public GeminiClient(Supplier<Settings> settings, SecretStore secrets) {
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

    /**
     * @param systemPrompt the persona (systemInstruction); may be empty
     * @param turns        the conversation so far, oldest first, starting with and ending on a user turn
     */
    public CompletableFuture<GeminiResult> generate(String systemPrompt, List<Turn> turns) {
        Settings.Gemini cfg = settings.get().gemini();
        String key = secrets.key();
        if (key.isEmpty()) return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NO_KEY, 0, "no API key is set (/scp079 setkey)", 0));
        if (inFlight.incrementAndGet() > cfg.maxConcurrent()) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.BUSY, 0,
                "gemini.max-concurrent-requests (" + cfg.maxConcurrent() + ") requests are already in flight", 0));
        }

        long started = System.nanoTime();
        HttpRequest request;
        try {
            // the model id is a path segment; encode it so that a typo in the config cannot reshape the URL
            String model = URLEncoder.encode(cfg.model(), StandardCharsets.UTF_8).replace("+", "%20");
            request = HttpRequest.newBuilder(URI.create(cfg.endpoint() + "/models/" + model + ":generateContent"))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body(cfg, systemPrompt, turns)), StandardCharsets.UTF_8))
                .build();
        } catch (IllegalArgumentException e) {
            inFlight.decrementAndGet();
            return CompletableFuture.completedFuture(GeminiResult.failure(GeminiResult.Error.NETWORK, 0,
                "gemini.endpoint is not a valid URL: " + secrets.scrub(String.valueOf(e.getMessage())), 0));
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .handle((response, error) -> {
                inFlight.decrementAndGet();
                long millis = (System.nanoTime() - started) / 1_000_000;
                try {
                    return error != null ? failure(error, cfg, millis) : interpret(response.statusCode(), response.body(), millis);
                } catch (RuntimeException e) {
                    return GeminiResult.failure(GeminiResult.Error.NETWORK, 0, "unexpected response: " + e, millis);
                }
            });
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

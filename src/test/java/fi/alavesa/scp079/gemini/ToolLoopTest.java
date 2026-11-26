package fi.alavesa.scp079.gemini;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import fi.alavesa.scp079.config.SecretStore;
import fi.alavesa.scp079.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** The function-calling loop against a local fake of the Gemini endpoint. */
class ToolLoopTest {

    @TempDir Path dir;

    @Test
    void callsTheToolAndSendsItsResultBack() throws Exception {
        List<JsonObject> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            requests.add(body);
            String reply = requests.size() == 1
                ? "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"open_door\",\"args\":{\"location\":\"cafeteria\"}},\"thoughtSignature\":\"sig1\"}]}}]}"
                : "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done.\"}]},\"finishReason\":\"STOP\"}]}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            Settings.Gemini gemini = new Settings.Gemini("test-model", "http://127.0.0.1:" + server.getAddress().getPort(), 0.5, 200, "", 10, 0L, 4);
            var canonical = Settings.class.getDeclaredConstructors()[0];
            Object[] nulls = new Object[canonical.getParameterCount()];
            for (int i = 0; i < nulls.length; i++) if (canonical.getParameterTypes()[i] == Settings.Gemini.class) nulls[i] = gemini;
            canonical.setAccessible(true);
            Settings settings = (Settings) canonical.newInstance(nulls);
            Path secrets = dir.resolve("secrets.yml");
            Files.writeString(secrets, "gemini-api-key: test-key\n");
            SecretStore store = new SecretStore(secrets, Logger.getAnonymousLogger());
            store.load();
            assumeKey(store);
            GeminiClient client = new GeminiClient(() -> settings, store);

            List<String> calls = new ArrayList<>();
            ToolRunner runner = (name, args) -> {
                calls.add(name + ":" + args);
                return CompletableFuture.completedFuture("Opened the door 'Cafeteria Door 1'.");
            };
            GeminiResult result = client.generate("persona", List.of(Turn.user("open the cafeteria door")),
                List.of(new ToolSpec("open_door", "opens", Map.of("location", "where"))), runner).get();

            assertTrue(result.ok(), result.detail());
            assertEquals("Done.", result.text());
            assertEquals(List.of("open_door:{location=cafeteria}"), calls);
            assertEquals(2, requests.size());
            assertTrue(requests.get(0).has("tools"));
            // the second request carries the model's own turn (with its thought signature) and the function result
            var contents = requests.get(1).getAsJsonArray("contents");
            assertEquals(3, contents.size());
            JsonObject modelTurn = contents.get(1).getAsJsonObject();
            assertEquals("sig1", modelTurn.getAsJsonArray("parts").get(0).getAsJsonObject().get("thoughtSignature").getAsString());
            JsonObject response = contents.get(2).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().getAsJsonObject("functionResponse");
            assertEquals("open_door", response.get("name").getAsString());
            assertEquals("Opened the door 'Cafeteria Door 1'.", response.getAsJsonObject("response").get("result").getAsString());
            client.close();
        } finally {
            server.stop(0);
        }
    }

    private static void assumeKey(SecretStore store) {
        org.junit.jupiter.api.Assumptions.assumeTrue(store.hasKey(), "secrets.yml format changed");
    }
}

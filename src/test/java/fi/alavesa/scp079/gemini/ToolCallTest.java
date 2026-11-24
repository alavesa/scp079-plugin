package fi.alavesa.scp079.gemini;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallTest {

    @Test
    void findsTheModelTurnWithFunctionCallsAndKeepsItWhole() {
        String body = """
            {"candidates":[{"content":{"parts":[{"functionCall":{"name":"open_door","args":{"location":"hall"}},"thoughtSignature":"abc"}]},"finishReason":"STOP"}]}""";
        JsonObject turn = GeminiClient.functionCallTurn(body);
        assertNotNull(turn);
        assertEquals("model", turn.get("role").getAsString());
        assertEquals("abc", turn.getAsJsonArray("parts").get(0).getAsJsonObject().get("thoughtSignature").getAsString());
    }

    @Test
    void plainTextIsNotAFunctionCall() {
        assertNull(GeminiClient.functionCallTurn("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}"));
        assertNull(GeminiClient.functionCallTurn("not json"));
        assertNull(GeminiClient.functionCallTurn(null));
    }

    @Test
    void declaresEveryParameterAsARequiredString() {
        var declared = GeminiClient.declarations(List.of(new ToolSpec("open_door", "Opens a door", Map.of("location", "where"))));
        JsonObject fn = declared.get(0).getAsJsonObject().getAsJsonArray("functionDeclarations").get(0).getAsJsonObject();
        assertEquals("open_door", fn.get("name").getAsString());
        JsonObject params = fn.getAsJsonObject("parameters");
        assertEquals("OBJECT", params.get("type").getAsString());
        assertEquals("STRING", params.getAsJsonObject("properties").getAsJsonObject("location").get("type").getAsString());
        assertEquals("location", params.getAsJsonArray("required").get(0).getAsString());
    }
}

package fi.alavesa.scp079.gemini;

/**
 * One message of a conversation, in the Gemini API's terms.
 *
 * @param role {@link #USER} or {@link #MODEL}
 */
public record Turn(String role, String text) {

    public static final String USER = "user", MODEL = "model";

    public static Turn user(String text) { return new Turn(USER, text); }

    public static Turn model(String text) { return new Turn(MODEL, text); }
}

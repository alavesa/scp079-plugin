package fi.alavesa.scp079.gemini;

/**
 * The outcome of one request - never an exception, so callers handle every case the same way.
 *
 * @param text   the model's raw reply; null unless {@link #ok()}
 * @param error  null on success
 * @param status HTTP status, or 0 when no response arrived
 * @param detail technical description for admins (never contains the API key); empty on success
 * @param millis how long the request took
 */
public record GeminiResult(String text, Error error, int status, String detail, long millis) {

    /** What went wrong, as far as the player-facing message is concerned. */
    public enum Error {
        /** No API key is configured. */
        NO_KEY("error.no-key"),
        /** 400 / 401 / 403: the key (or the request, e.g. an unknown model) was rejected. */
        BAD_KEY("error.bad-key"),
        /** 429: rate limit or quota - the free tier has both. */
        RATE_LIMIT("error.rate-limit"),
        /** 5xx. */
        SERVER("error.server"),
        TIMEOUT("error.timeout"),
        /** The prompt or the answer was blocked by Google's safety filters. */
        BLOCKED("error.blocked"),
        /** A 200 without any text (often: the token limit was used up by "thinking"). */
        EMPTY("error.empty"),
        /** Too many requests in flight (gemini.max-concurrent-requests). */
        BUSY("error.busy"),
        /** DNS, TLS, connection reset... or any other status. */
        NETWORK("error.network");

        private final String messageKey;

        Error(String messageKey) { this.messageKey = messageKey; }

        /** Key of the in-character line in messages.yml. */
        public String messageKey() { return messageKey; }
    }

    public boolean ok() { return error == null; }

    static GeminiResult success(String text, int status, long millis) { return new GeminiResult(text, null, status, "", millis); }

    static GeminiResult failure(Error error, int status, String detail, long millis) { return new GeminiResult(null, error, status, detail, millis); }
}

package fi.alavesa.scp079.gemini;

import java.util.Map;

/** A function the model may call: every parameter is a required string. */
public record ToolSpec(String name, String description, Map<String, String> parameters) { }

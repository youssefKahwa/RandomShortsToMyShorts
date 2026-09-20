package com.rstms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rstms.config.AppProperties;
import com.rstms.model.SegmentSpec;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Translates already-transcribed segments into natural, TTS-ready English using Claude, in one
 * batched request per job rather than one call per line. Whisper (free, local) already did the
 * expensive part - transcription; Claude's only job is translating a handful of short lines with
 * full-video context, so token volume per job is small regardless of model choice.
 *
 * Requires an Anthropic API key in the ANTHROPIC_API_KEY environment variable. This is the paid
 * Anthropic API (console.anthropic.com), billed separately from any claude.ai subscription - the
 * two are different products with different billing.
 */
@Component
public class ClaudeTranslationService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String TOOL_NAME = "provide_translations";

    private final AppProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ClaudeTranslationService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }

    public List<SegmentSpec> translate(List<SegmentSpec> originalSegments, String sourceLanguage, String context) {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY n'est pas défini. Ce moteur utilise l'API Anthropic payante "
                    + "(console.anthropic.com) - distincte d'un abonnement claude.ai. Créez une clé API "
                    + "et définissez-la comme variable d'environnement avant de relancer.");
        }

        try {
            String requestBody = buildRequestBody(originalSegments, sourceLanguage, context);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "L'API Claude a répondu " + response.statusCode() + " : " + response.body());
            }

            List<String> translations = parseTranslations(response.body(), originalSegments.size());

            List<SegmentSpec> result = new ArrayList<>();
            for (int i = 0; i < originalSegments.size(); i++) {
                SegmentSpec original = originalSegments.get(i);
                SegmentSpec translated = new SegmentSpec();
                translated.setStart(original.getStart());
                translated.setEnd(original.getEnd());
                translated.setVoice(original.getVoice());
                translated.setText(translations.get(i));
                result.add(translated);
            }
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Échec de la traduction via Claude : " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(List<SegmentSpec> segments, String sourceLanguage, String context) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getAutoScript().getClaudeModel());
        root.put("max_tokens", props.getAutoScript().getClaudeMaxOutputTokens());
        root.put("system", buildSystemPrompt(sourceLanguage, context));

        ArrayNode linesJson = mapper.createArrayNode();
        for (int i = 0; i < segments.size(); i++) {
            SegmentSpec seg = segments.get(i);
            ObjectNode line = mapper.createObjectNode();
            line.put("index", i);
            line.put("durationSeconds", Math.round((seg.getEnd() - seg.getStart()) * 10.0) / 10.0);
            line.put("originalText", seg.getText());
            linesJson.add(line);
        }

        ObjectNode userMessage = mapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", "Translate each line below into English. Respond only via the "
                + TOOL_NAME + " tool, with exactly " + segments.size() + " entries in the same order.\n\n"
                + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linesJson));
        ArrayNode messages = mapper.createArrayNode();
        messages.add(userMessage);
        root.set("messages", messages);

        root.set("tools", buildToolDefinition());
        ObjectNode toolChoice = mapper.createObjectNode();
        toolChoice.put("type", "tool");
        toolChoice.put("name", TOOL_NAME);
        root.set("tool_choice", toolChoice);

        return mapper.writeValueAsString(root);
    }

    private String buildSystemPrompt(String sourceLanguage, String context) {
        StringBuilder system = new StringBuilder();
        system.append("You are translating spoken narration from a short-form video (YouTube Shorts, TikTok, ")
              .append("or Instagram Reels) from language code '").append(sourceLanguage).append("' into natural, ")
              .append("engaging spoken English. This is not a formal or literal translation: it should sound ")
              .append("like something a native English speaker would actually say out loud, matching the energy ")
              .append("and tone of short-form social video content. Each line will be read aloud by a ")
              .append("text-to-speech voice and must fit within the given time budget, so keep each translation ")
              .append("concise enough to be spoken naturally in roughly that many seconds (about 2.3 spoken ")
              .append("words per second as a rough guide) - do not pad, ramble, or add filler. Preserve names, ")
              .append("numbers and proper nouns accurately. Use all the lines together as context to keep ")
              .append("terminology, tone and continuity consistent across the whole video.");
        if (context != null && !context.isBlank()) {
            system.append(" Additional context from the creator: ").append(context.trim());
        }
        return system.toString();
    }

    private ArrayNode buildToolDefinition() {
        ObjectNode itemsSchema = mapper.createObjectNode();
        itemsSchema.put("type", "string");

        ObjectNode translationsProp = mapper.createObjectNode();
        translationsProp.put("type", "array");
        translationsProp.set("items", itemsSchema);

        ObjectNode properties = mapper.createObjectNode();
        properties.set("translations", translationsProp);

        ArrayNode required = mapper.createArrayNode();
        required.add("translations");

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);

        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Provide the English translation for each numbered line, in the same order.");
        tool.set("input_schema", schema);

        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool);
        return tools;
    }

    private List<String> parseTranslations(String responseBody, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(responseBody);
        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asText()) && TOOL_NAME.equals(block.path("name").asText())) {
                List<String> result = new ArrayList<>();
                block.path("input").path("translations").forEach(n -> result.add(n.asText()));
                if (result.size() != expectedCount) {
                    throw new IllegalStateException(String.format(Locale.ROOT,
                            "Claude a renvoyé %d traductions au lieu de %d attendues", result.size(), expectedCount));
                }
                return result;
            }
        }
        throw new IllegalStateException("Réponse Claude inattendue (pas d'appel d'outil " + TOOL_NAME + ") : " + responseBody);
    }
}

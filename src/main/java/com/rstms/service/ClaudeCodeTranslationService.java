package com.rstms.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rstms.config.AppProperties;
import com.rstms.model.SegmentSpec;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Translates already-transcribed segments into natural, TTS-ready English by shelling out to the
 * local Claude Code CLI (bundled with the VS Code extension) instead of calling the Anthropic API
 * directly. This uses whatever authentication Claude Code already has configured - for most users
 * that's their claude.ai subscription login (Pro/Max), not a separately-billed API key.
 *
 * Real tradeoff, stated plainly: unlike ClaudeTranslationService's raw API call (a few hundred
 * tokens), each Claude Code invocation carries the CLI's normal overhead - its default system
 * prompt and tool definitions - so it draws more heavily on the subscription's usage pool per
 * call. The default system prompt is intentionally left untouched (no --system-prompt override)
 * because Anthropic's prompt caching keeps that default prompt warm from the user's ordinary
 * Claude Code usage; overriding it starts a new, uncached (more expensive) cache entry instead.
 *
 * The prompt is piped over stdin rather than passed as a command-line argument, and there's no
 * --json-schema flag: both were tried and failed in practice - Java's ProcessBuilder mangles a
 * long argument containing embedded quotes on Windows (the schema arrived at the child process
 * truncated/invalid), and the CLI's own "no stdin data received" warning confirmed it expects the
 * prompt on stdin when none is given positionally. Asking for JSON in the prompt text and parsing
 * "result" directly sidesteps both problems and has proven reliable in testing.
 */
@Component
public class ClaudeCodeTranslationService {

    private final AppProperties props;
    private final ObjectMapper mapper;

    public ClaudeCodeTranslationService(AppProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public List<SegmentSpec> translate(List<SegmentSpec> originalSegments, String sourceLanguage, String context) {
        String binary = props.getAutoScript().getClaudeCodeBinary();
        if (binary == null || binary.isBlank() || !Files.isRegularFile(Path.of(binary))) {
            throw new IllegalStateException(
                    "app.auto-script.claude-code-binary n'est pas configuré ou introuvable ('" + binary + "'). "
                    + "Trouvez claude.exe sous .vscode/extensions/anthropic.claude-code-*/resources/native-binary/ "
                    + "(le chemin change à chaque mise à jour de l'extension) et indiquez-le dans application.yml.");
        }

        try {
            String prompt = buildPrompt(originalSegments, sourceLanguage, context);

            List<String> cmd = List.of(
                    binary,
                    "-p",
                    "--output-format", "json",
                    "--model", props.getAutoScript().getClaudeCodeModel(),
                    "--disallowedTools", "Bash", "Read", "Write", "Edit", "Glob", "Grep",
                    "WebFetch", "WebSearch", "Task", "NotebookEdit"
            );

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();

            try (Writer stdin = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                stdin.write(prompt);
            }

            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread errThread = new Thread(() -> readInto(p.getErrorStream(), err));
            errThread.start();
            readInto(p.getInputStream(), out);
            errThread.join(5000);

            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("Claude Code n'a pas répondu après 180s");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("Claude Code a échoué (code " + p.exitValue() + ") : " + err);
            }

            List<String> translations = parseTranslations(out.toString(), originalSegments.size());

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
            throw new IllegalStateException("Échec de la traduction via Claude Code : " + e.getMessage(), e);
        }
    }

    private String buildPrompt(List<SegmentSpec> segments, String sourceLanguage, String context) throws Exception {
        ArrayNode lines = mapper.createArrayNode();
        for (int i = 0; i < segments.size(); i++) {
            SegmentSpec seg = segments.get(i);
            ObjectNode line = mapper.createObjectNode();
            line.put("index", i);
            line.put("durationSeconds", Math.round((seg.getEnd() - seg.getStart()) * 10.0) / 10.0);
            line.put("originalText", seg.getText());
            lines.add(line);
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("Translate each line below from language code '").append(sourceLanguage)
              .append("' into natural, engaging spoken English suitable for a short-form video ")
              .append("(YouTube Shorts / TikTok / Instagram Reels) narration - not a formal or literal ")
              .append("translation. Each line is read aloud by text-to-speech and must fit its given time ")
              .append("budget, so keep it concise enough to be spoken naturally in roughly that many seconds ")
              .append("(about 2.3 spoken words per second). Preserve names, numbers and proper nouns. Use all ")
              .append("lines together as context to keep terminology and tone consistent.");
        if (context != null && !context.isBlank()) {
            prompt.append(" Additional context from the creator: ").append(context.trim());
        }
        prompt.append(" Respond with ONLY a JSON object of the exact shape {\"translations\":[\"...\", ...]} ")
              .append("containing exactly ").append(segments.size())
              .append(" entries in the same order as the input, no other text before or after. Lines:\n")
              .append(mapper.writeValueAsString(lines));
        return prompt.toString();
    }

    private List<String> parseTranslations(String stdout, int expectedCount) throws Exception {
        JsonNode root = mapper.readTree(stdout);
        JsonNode translationsNode = root.path("result");
        JsonNode parsedResult = translationsNode.isTextual()
                ? mapper.readTree(stripCodeFence(translationsNode.asText()))
                : translationsNode;

        List<String> result = new ArrayList<>();
        parsedResult.path("translations").forEach(n -> result.add(n.asText()));
        if (result.size() != expectedCount) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "Claude Code a renvoyé %d traductions au lieu de %d attendues : %s", result.size(), expectedCount, stdout));
        }
        return result;
    }

    /**
     * Despite being told to answer with ONLY a JSON object, Claude occasionally wraps its "result"
     * text in a markdown code fence anyway (more often on longer/more complex batches than on short
     * test prompts) - a well-known LLM quirk, not something a stricter prompt reliably prevents.
     * Stripping an optional ```/```json fence before parsing avoids failing the whole job over it.
     */
    private static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) trimmed = trimmed.substring(firstNewline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
            trimmed = trimmed.strip();
        }
        return trimmed;
    }

    private static void readInto(java.io.InputStream stream, StringBuilder sb) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) { }
    }
}

package com.review.worker.prompt;

import com.review.worker.config.WorkerProperties;
import com.review.worker.error.AbandonJobException;
import com.review.worker.llama.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Gateway-supplied {@code promptVersion} + diff (+ optional {@code chunkContext}, V2 diff
 * chunking) into the exact chat messages and model parameters to send to llama-server.
 *
 * <p>{@code promptVersion}, {@code diff}, and {@code chunkContext} are all untrusted, Gateway-supplied
 * input (the Gateway itself only forwards what GitLab CI submitted, or — for {@code chunkContext} —
 * derived from MR-author-controlled file names) and are treated accordingly:
 * <ul>
 *   <li>WSR-01: {@code promptVersion} is checked against an allowlist regex <em>before</em> it is used
 *       to build any resource path — structurally excluding path traversal, since {@code '/'} is not in
 *       the allowed character class.</li>
 *   <li>WSR-07: templates are resolved only via {@link ClassPathResource}, i.e. only from inside the fat
 *       JAR — {@code prompt.location} is validated at startup ({@code WorkerProperties}) to start with
 *       {@code "classpath:"}, so there is no reachable code path to an operator-writable directory.</li>
 *   <li>WSR-02/CSR-08: substitution is a <b>single regex pass</b> over {@code \{\{(DIFF|CHUNK_CONTEXT)\}\}}
 *       using {@code Matcher.appendReplacement}/{@code Matcher.quoteReplacement} — never re-parsed as a
 *       template/expression engine, and never re-scanned after substitution. Two sequential
 *       {@code String.replace} calls (one per placeholder) would be unsafe: {@code replace} re-scans its
 *       own output, so a file literally named {@code {{DIFF}}} inside {@code chunkContext}, or a diff
 *       containing the literal text {@code {{CHUNK_CONTEXT}}}, would cause cross-substitution. The
 *       single-pass {@link Matcher} approach replaces each placeholder exactly once, from the original
 *       template text only.</li>
 *   <li>CSR-08 (defense in depth): literal {@code {{}}} sequences are stripped from the
 *       Gateway-rendered {@code chunkContext} before it is ever substituted in.</li>
 *   <li>CSR-12: if {@code chunkContext != null} but the resolved template has no
 *       {@code {{CHUNK_CONTEXT}}} placeholder at all, the job is abandoned rather than silently
 *       proceeding without the guardrail (architecture D6).</li>
 *   <li>WSR-03: the combined diff + chunkContext size is bounded independently of whatever the
 *       Gateway itself enforces.</li>
 * </ul>
 */
@Component
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final Pattern PROMPT_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(DIFF|CHUNK_CONTEXT)\\}\\}");
    private static final String DIFF_TOKEN = "DIFF";
    private static final String CHUNK_CONTEXT_PLACEHOLDER = "{{CHUNK_CONTEXT}}";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final WorkerProperties properties;

    public PromptTemplateService(WorkerProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws AbandonJobException if {@code promptVersion} fails the allowlist check, no matching
     *                              template exists on the classpath, the template is malformed, the
     *                              combined diff/chunkContext size exceeds
     *                              {@code worker.limits.max-diff-bytes}, or {@code chunkContext} was
     *                              supplied but the template has no {@code {{CHUNK_CONTEXT}}}
     *                              placeholder (CSR-12).
     */
    public ResolvedPrompt resolve(String promptVersion, String diff, String chunkContext) {
        validatePromptVersion(promptVersion);
        validateDiffSize(diff, chunkContext);
        PromptTemplate template = loadTemplate(promptVersion);
        if (chunkContext != null && !hasChunkContextPlaceholder(template)) {
            throw new AbandonJobException(
                    "chunkContext was supplied but promptVersion '" + promptVersion + "' has no {{CHUNK_CONTEXT}} placeholder");
        }
        // CSR-08 defense in depth: strip literal '{{'/'}}' from the Gateway-rendered chunkContext
        // before it is ever substituted in, on top of the single-pass substitution below.
        String sanitizedChunkContext = chunkContext == null ? null : chunkContext.replace("{{", "").replace("}}", "");
        List<ChatMessage> messages = buildMessages(template, diff, sanitizedChunkContext);
        String model = template.model() != null ? template.model() : properties.getLlama().getModel();
        double temperature = template.temperature() != null
                ? template.temperature() : properties.getLlama().getTemperature();
        int maxTokens = template.maxTokens() != null
                ? template.maxTokens() : properties.getLlama().getMaxTokens();
        return new ResolvedPrompt(messages, model, temperature, maxTokens);
    }

    /** Backward-compatible overload for callers with no chunk context (single-chunk Reviews). */
    public ResolvedPrompt resolve(String promptVersion, String diff) {
        return resolve(promptVersion, diff, null);
    }

    private boolean hasChunkContextPlaceholder(PromptTemplate template) {
        return (template.system() != null && template.system().contains(CHUNK_CONTEXT_PLACEHOLDER))
                || (template.user() != null && template.user().contains(CHUNK_CONTEXT_PLACEHOLDER));
    }

    private void validatePromptVersion(String promptVersion) {
        boolean valid = promptVersion != null
                && PROMPT_VERSION_PATTERN.matcher(promptVersion).matches()
                && !promptVersion.contains("..");
        if (!valid) {
            // The raw value is untrusted (possibly attacker-controlled) and must never be logged
            // verbatim (WSR-18, log-injection risk); only its length is safe to report.
            log.warn("Rejected promptVersion (length={})", promptVersion == null ? 0 : promptVersion.length());
            throw new AbandonJobException("Unknown or invalid promptVersion");
        }
    }

    /** Extends {@code worker.limits.max-diff-bytes} to cover {@code diff bytes + chunkContext bytes} combined. */
    private void validateDiffSize(String diff, String chunkContext) {
        int diffBytes = diff == null ? 0 : diff.getBytes(StandardCharsets.UTF_8).length;
        int contextBytes = chunkContext == null ? 0 : chunkContext.getBytes(StandardCharsets.UTF_8).length;
        long combinedBytes = (long) diffBytes + contextBytes;
        long maxDiffBytes = properties.getWorker().getLimits().getMaxDiffBytes();
        if (combinedBytes > maxDiffBytes) {
            throw new AbandonJobException("diff + chunkContext exceeds worker.limits.max-diff-bytes (" + maxDiffBytes + ")");
        }
    }

    private PromptTemplate loadTemplate(String promptVersion) {
        String location = properties.getPrompt().getLocation(); // guaranteed to start with "classpath:"
        String classpathPath = location.substring(CLASSPATH_PREFIX.length()) + promptVersion + ".yml";
        ClassPathResource resource = new ClassPathResource(classpathPath);
        if (!resource.exists()) {
            throw new AbandonJobException("Unknown promptVersion: no matching template on classpath");
        }
        Map<String, Object> parsed = parseYaml(resource, promptVersion);
        String user = asString(parsed.get("user"));
        if (user == null || user.isBlank()) {
            throw new AbandonJobException("Prompt template '" + promptVersion + "' has no 'user' section");
        }
        String system = asString(parsed.get("system"));
        String model = asString(parsed.get("model"));
        Double temperature = asDouble(parsed.get("temperature"));
        Integer maxTokens = asInteger(parsed.get("maxTokens"));
        return new PromptTemplate(system, user, model, temperature, maxTokens);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(ClassPathResource resource, String promptVersion) {
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                throw new AbandonJobException("Prompt template '" + promptVersion + "' is not a YAML mapping");
            }
            return (Map<String, Object>) loaded;
        } catch (IOException e) {
            throw new AbandonJobException("Could not read prompt template '" + promptVersion + "'", e);
        }
    }

    private List<ChatMessage> buildMessages(PromptTemplate template, String diff, String chunkContext) {
        List<ChatMessage> messages = new ArrayList<>();
        if (template.system() != null && !template.system().isBlank()) {
            messages.add(new ChatMessage("system", substitute(template.system(), diff, chunkContext)));
        }
        messages.add(new ChatMessage("user", substitute(template.user(), diff, chunkContext)));
        return messages;
    }

    /**
     * WSR-02/CSR-08: single regex pass over {@code \{\{(DIFF|CHUNK_CONTEXT)\}\}}, replacing each match
     * exactly once from the original template text — deliberately NOT two sequential
     * {@code String.replace} calls (which would re-scan already-substituted output, letting one
     * placeholder's replacement content accidentally satisfy the other placeholder's pattern) and NOT
     * re-parsed by any expression/template engine.
     */
    private String substitute(String templateText, String diff, String chunkContext) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(templateText);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            String replacement = DIFF_TOKEN.equals(token)
                    ? (diff == null ? "" : diff)
                    : (chunkContext == null ? "" : chunkContext);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }
}

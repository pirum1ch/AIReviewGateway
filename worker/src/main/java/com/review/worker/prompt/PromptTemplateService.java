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
import java.util.regex.Pattern;

/**
 * Resolves a Gateway-supplied {@code promptVersion} + diff into the exact chat messages and model
 * parameters to send to llama-server.
 *
 * <p>Both {@code promptVersion} and {@code diff} are untrusted, Gateway-supplied input (the Gateway
 * itself only forwards what GitLab CI submitted) and are treated accordingly:
 * <ul>
 *   <li>WSR-01: {@code promptVersion} is checked against an allowlist regex <em>before</em> it is used
 *       to build any resource path — structurally excluding path traversal, since {@code '/'} is not in
 *       the allowed character class.</li>
 *   <li>WSR-07: templates are resolved only via {@link ClassPathResource}, i.e. only from inside the fat
 *       JAR — {@code prompt.location} is validated at startup ({@code WorkerProperties}) to start with
 *       {@code "classpath:"}, so there is no reachable code path to an operator-writable directory.</li>
 *   <li>WSR-02: the diff is substituted into the template with a single literal
 *       {@link String#replace(CharSequence, CharSequence)} — never re-parsed as a template/expression
 *       (no SpEL, Freemarker, or {@code ${}} evaluation of any kind).</li>
 *   <li>WSR-03: the diff size is bounded independently of whatever the Gateway itself enforces.</li>
 * </ul>
 */
@Component
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);

    private static final Pattern PROMPT_VERSION_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final String DIFF_PLACEHOLDER = "{{DIFF}}";
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final WorkerProperties properties;

    public PromptTemplateService(WorkerProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws AbandonJobException if {@code promptVersion} fails the allowlist check, no matching
     *                              template exists on the classpath, the template is malformed, or the
     *                              diff exceeds {@code worker.limits.max-diff-bytes}.
     */
    public ResolvedPrompt resolve(String promptVersion, String diff) {
        validatePromptVersion(promptVersion);
        validateDiffSize(diff);
        PromptTemplate template = loadTemplate(promptVersion);
        List<ChatMessage> messages = buildMessages(template, diff);
        String model = template.model() != null ? template.model() : properties.getLlama().getModel();
        double temperature = template.temperature() != null
                ? template.temperature() : properties.getLlama().getTemperature();
        int maxTokens = template.maxTokens() != null
                ? template.maxTokens() : properties.getLlama().getMaxTokens();
        return new ResolvedPrompt(messages, model, temperature, maxTokens);
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

    private void validateDiffSize(String diff) {
        int diffBytes = diff == null ? 0 : diff.getBytes(StandardCharsets.UTF_8).length;
        long maxDiffBytes = properties.getWorker().getLimits().getMaxDiffBytes();
        if (diffBytes > maxDiffBytes) {
            throw new AbandonJobException("Diff exceeds worker.limits.max-diff-bytes (" + maxDiffBytes + ")");
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

    private List<ChatMessage> buildMessages(PromptTemplate template, String diff) {
        List<ChatMessage> messages = new ArrayList<>();
        if (template.system() != null && !template.system().isBlank()) {
            messages.add(new ChatMessage("system", substituteDiff(template.system(), diff)));
        }
        messages.add(new ChatMessage("user", substituteDiff(template.user(), diff)));
        return messages;
    }

    private String substituteDiff(String text, String diff) {
        // WSR-02: literal substitution only -- deliberately NOT re-parsed by any expression/template engine.
        return text.replace(DIFF_PLACEHOLDER, diff == null ? "" : diff);
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

package com.micomm.email.template;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.util.HtmlUtils;

/**
 * Fills {{name}} placeholders. Values are HTML-escaped in HTML bodies so variable
 * content (member names, ...) cannot inject markup; subjects are plain text.
 * Every placeholder must have a value: a missing one is an error, never sent as-is.
 */
public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");

    private TemplateRenderer() {
    }

    /** Names of the placeholders in {@code template}, in order of first appearance. */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    public static String renderText(String template, Map<String, String> variables) {
        return render(template, variables, false);
    }

    public static String renderHtml(String template, Map<String, String> variables) {
        return render(template, variables, true);
    }

    private static String render(String template, Map<String, String> variables, boolean escapeHtml) {
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            if (value == null) {
                missing.add(m.group(1));
                value = "";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(escapeHtml ? HtmlUtils.htmlEscape(value) : value));
        }
        m.appendTail(out);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing template variables: " + String.join(", ", missing));
        }
        return out.toString();
    }
}

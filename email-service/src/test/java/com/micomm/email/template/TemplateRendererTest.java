package com.micomm.email.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void fillsPlaceholdersIncludingRepeatsAndSpacing() {
        assertThat(TemplateRenderer.renderText("Hi {{name}}, welcome to {{ club }}. Bye {{name}}",
                Map.of("name", "Sam", "club", "KCC")))
                .isEqualTo("Hi Sam, welcome to KCC. Bye Sam");
    }

    @Test
    void escapesValuesInHtmlButNotTheTemplate() {
        assertThat(TemplateRenderer.renderHtml("<p>Hi {{name}}</p>", Map.of("name", "<b>Sam</b> & co")))
                .isEqualTo("<p>Hi &lt;b&gt;Sam&lt;/b&gt; &amp; co</p>");
    }

    @Test
    void subjectsAreNotEscaped() {
        assertThat(TemplateRenderer.renderText("{{a}}", Map.of("a", "Tom & Jerry"))).isEqualTo("Tom & Jerry");
    }

    @Test
    void valuesWithDollarSignsAndBackslashesAreLiteral() {
        assertThat(TemplateRenderer.renderText("Fee: {{fee}}", Map.of("fee", "$1\\0"))).isEqualTo("Fee: $1\\0");
    }

    @Test
    void missingVariablesAreRejectedAndAllListed() {
        assertThatThrownBy(() -> TemplateRenderer.renderHtml("{{a}} {{b}} {{c}}", Map.of("b", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing template variables: a, c");
    }

    @Test
    void listsPlaceholdersOnceInOrder() {
        assertThat(TemplateRenderer.placeholders("{{b}} {{ a }} {{b}}")).containsExactly("b", "a");
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        assertThat(TemplateRenderer.renderHtml("<p>{ not a {placeholder} }</p>", Map.of()))
                .isEqualTo("<p>{ not a {placeholder} }</p>");
    }
}

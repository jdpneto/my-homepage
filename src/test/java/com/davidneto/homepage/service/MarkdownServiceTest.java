package com.davidneto.homepage.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownServiceTest {

    private final MarkdownService markdownService = new MarkdownService();

    @Test
    void renderHeading() {
        String html = markdownService.render("# Hello");
        assertThat(html).contains("<h1>Hello</h1>");
    }

    @Test
    void renderParagraph() {
        String html = markdownService.render("Some text");
        assertThat(html).contains("<p>Some text</p>");
    }

    @Test
    void renderCodeBlock() {
        String html = markdownService.render("```java\nint x = 1;\n```");
        assertThat(html).contains("<code");
        assertThat(html).contains("int x = 1;");
    }

    @Test
    void renderNullReturnsEmpty() {
        String html = markdownService.render(null);
        assertThat(html).isEmpty();
    }

    @Test
    void renderEmptyReturnsEmpty() {
        String html = markdownService.render("");
        assertThat(html).isEmpty();
    }
}

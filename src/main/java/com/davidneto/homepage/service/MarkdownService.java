package com.davidneto.homepage.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

@Service
public class MarkdownService {

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().build();

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }
}

package com.davidneto.homepage.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MarkdownService {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).build();
    private final PolicyFactory sanitizer = new HtmlPolicyBuilder()
            .allowCommonBlockElements()
            .allowCommonInlineFormattingElements()
            .allowElements("a", "img", "pre", "code", "br", "hr", "table", "thead", "tbody", "tr", "th", "td")
            .allowUrlProtocols("https", "http")
            .allowAttributes("href").onElements("a")
            .allowAttributes("src", "alt").onElements("img")
            .allowAttributes("class").onElements("code", "pre")
            .toFactory();

    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        String html = renderer.render(parser.parse(markdown));
        return sanitizer.sanitize(html);
    }
}

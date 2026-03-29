package com.davidneto.homepage.service;

import java.util.function.Predicate;

public final class SlugGenerator {

    private SlugGenerator() {}

    public static String generate(String title, Predicate<String> existsCheck) {
        String base = title.toLowerCase()
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");

        String slug = base;
        int counter = 2;
        while (existsCheck.test(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }
}

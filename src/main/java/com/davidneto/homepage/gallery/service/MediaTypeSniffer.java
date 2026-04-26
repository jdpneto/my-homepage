package com.davidneto.homepage.gallery.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MediaTypeSniffer {

    private MediaTypeSniffer() {}

    static String sniff(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(32);
            if (head.length < 4) return null;

            // JPEG: FF D8 FF
            if ((head[0] & 0xff) == 0xFF && (head[1] & 0xff) == 0xD8 && (head[2] & 0xff) == 0xFF)
                return "image/jpeg";
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if ((head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G')
                return "image/png";
            // WebP: RIFF....WEBP
            if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P')
                return "image/webp";
            // HEIC: ftypheic / ftypheix / ftypmif1 (also covers HEIF variants)
            if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
                String brand = new String(head, 8, 4);
                if (brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("heif")
                        || brand.equals("msf1") || brand.equals("hevc") || brand.equals("hevx")
                        || brand.equals("MiHE") || brand.equals("MiPr"))
                    return "image/heic";
                if (brand.equals("isom") || brand.equals("mp42") || brand.equals("mp41") || brand.equals("avc1"))
                    return "video/mp4";
                if (brand.equals("qt  "))
                    return "video/quicktime";
            }
            return null;
        }
    }

    public static String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/heic" -> "heic";
            case "image/webp" -> "webp";
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            default -> "bin";
        };
    }
}

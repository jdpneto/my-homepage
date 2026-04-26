package com.davidneto.homepage.gallery.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMetadataExtractorTest {

    @Test
    void parsesFfprobeJson() {
        String json = """
                {
                  "streams": [
                    {"codec_type": "video", "width": 1920, "height": 1080}
                  ],
                  "format": {
                    "duration": "12.345",
                    "tags": { "creation_time": "2018-04-12T15:30:00.000000Z" }
                  }
                }
                """;
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> json);

        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isEqualTo(1920);
        assertThat(r.height()).isEqualTo(1080);
        assertThat(r.durationSeconds()).isEqualTo(12);
        assertThat(r.takenAt()).isEqualTo(LocalDateTime.of(2018, 4, 12, 15, 30, 0));
    }

    @Test
    void returnsNullsWhenJsonMissingFields() {
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> "{}");
        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isNull();
        assertThat(r.height()).isNull();
        assertThat(r.durationSeconds()).isNull();
        assertThat(r.takenAt()).isNull();
    }

    @Test
    void emptyOutputReturnsAllNulls() {
        VideoMetadataExtractor x = new VideoMetadataExtractor((cmd) -> "");
        VideoMetadataExtractor.Result r = x.extract(Path.of("/dev/null"));
        assertThat(r.width()).isNull();
        assertThat(r.takenAt()).isNull();
    }
}

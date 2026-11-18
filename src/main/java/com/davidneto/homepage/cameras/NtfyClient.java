package com.davidneto.homepage.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** ntfy: {@code POST <url>/<topic>} with the message as the body. Text only, never footage. */
@Component
public class NtfyClient implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(NtfyClient.class);

    private final CameraProperties props;
    private final RestClient http;
    private boolean warnedNoTopic;

    @Autowired
    public NtfyClient(CameraProperties props) {
        this(props, RestClient.builder().requestFactory(timeouts()));
    }

    /** For tests: the builder is used as given (e.g. bound to a MockRestServiceServer). */
    NtfyClient(CameraProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.build();
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(10));
        rf.setReadTimeout(Duration.ofSeconds(10));
        return rf;
    }

    @Override
    public boolean send(String title, String message, String priority) {
        String topic = props.getNtfyTopic();
        if (topic == null || topic.isBlank()) {
            if (!warnedNoTopic) {
                log.info("ntfy topic not configured - camera alerts are only logged");
                warnedNoTopic = true;
            }
            log.info("camera alert (not sent): {} - {}", title, message);
            return false;
        }
        String url = props.getNtfyUrl().replaceAll("/+$", "") + "/" + topic;
        try {
            http.post().uri(url)
                    // RFC 2047 so non-ASCII titles survive the header (ntfy decodes it)
                    .header("Title", encodeHeader(title))
                    .header("Priority", priority)
                    .header("Tags", "high".equals(priority) ? "warning" : "white_check_mark")
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            // The topic is the secret and exception messages contain the URL: redact it.
            log.warn("ntfy push failed ({}): {}", title, e.toString().replace(topic, "***"));
            return false;
        }
    }

    private static String encodeHeader(String value) {
        boolean ascii = value.chars().allMatch(c -> c >= 0x20 && c < 0x7f);
        if (ascii) return value;
        return "=?UTF-8?B?" + java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}

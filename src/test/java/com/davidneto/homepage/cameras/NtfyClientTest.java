package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class NtfyClientTest {

    CameraProperties props(String topic) {
        CameraProperties p = new CameraProperties();
        p.setNtfyUrl("https://ntfy.sh");
        p.setNtfyTopic(topic);
        return p;
    }

    @Test
    void postsTitleBodyAndPriority() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/topic123"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Title", "Camera storage almost full"))
                .andExpect(header("Priority", "high"))
                .andExpect(content().string("camera quota 90% used"))
                .andRespond(withSuccess());
        boolean sent = new NtfyClient(props("topic123"), builder).send("Camera storage almost full",
                "camera quota 90% used", "high");
        assertThat(sent).isTrue();
        server.verify();
    }

    @Test
    void clickAndTagsHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/topic123"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Priority", "high"))
                .andExpect(header("Tags", "bust_in_silhouette"))
                .andExpect(header("Click", "https://davidneto.eu/admin/cameras?camera=Living%20Room&trigger=person"))
                .andExpect(content().string("Away mode"))
                .andRespond(withSuccess());
        boolean sent = new NtfyClient(props("topic123"), builder).send("Person · Living Room · 14:58", "Away mode",
                "high", "bust_in_silhouette", "https://davidneto.eu/admin/cameras?camera=Living%20Room&trigger=person");
        assertThat(sent).isTrue();
        server.verify();
    }

    @Test
    void plainSendKeepsItsTagsAndHasNoClick() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/topic123"))
                .andExpect(header("Tags", "warning"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Click"))
                .andRespond(withSuccess());
        assertThat(new NtfyClient(props("topic123"), builder).send("t", "m", "high")).isTrue();
        server.verify();
    }

    @Test
    void noTopicNoCrash() {
        assertThat(new NtfyClient(props(""), RestClient.builder()).send("t", "m", "high")).isFalse();
    }

    @Test
    void serverErrorIsSwallowed() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/topic123")).andRespond(withServerError());
        assertThat(new NtfyClient(props("topic123"), builder).send("t", "m", "high")).isFalse();
    }

    @Test
    void nonAsciiTitleIsSent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/topic123")).andRespond(withSuccess());
        assertThat(new NtfyClient(props("topic123"), builder).send("Câmara: armazenamento", "é", "high")).isTrue();
    }

    @Test
    void failureLogNeverContainsTheTopic(org.springframework.boot.test.system.CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://ntfy.sh/secret-topic-abc"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withException(new java.net.SocketTimeoutException("connect timed out")));
        new NtfyClient(props("secret-topic-abc"), builder).send("t", "m", "high");
        assertThat(output.getAll()).contains("ntfy push failed").doesNotContain("secret-topic-abc");
    }
}

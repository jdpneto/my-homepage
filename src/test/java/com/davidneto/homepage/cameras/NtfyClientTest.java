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
}

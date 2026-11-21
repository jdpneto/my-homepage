package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModeRequestsTest {

    static final Instant T0 = Instant.parse("2026-11-21T10:00:00Z");

    @TempDir Path root;
    AlertCheckerTest.MutableClock clock;
    ClipFiles files;
    ModeRequests requests;
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws Exception {
        CameraProperties props = new CameraProperties();
        props.setRootDir(root);
        props.setThumbsDir(root.resolveSibling(root.getFileName() + "-thumbs"));
        files = new ClipFiles(props);
        clock = new AlertCheckerTest.MutableClock(T0);
        requests = new ModeRequests(files, clock);
        Files.writeString(root.resolve(ClipFiles.VOLUME_MARKER), ClipFilesTest.MARKER_TEXT);
    }

    void heartbeat(String body) throws Exception {
        Files.writeString(root.resolve(ClipFiles.HEARTBEAT), body);
    }

    @Test
    void writesRequestFileAtomically() throws Exception {
        Files.writeString(root.resolve(ModeRequests.FILE), "{\"id\":\"old\",\"mode\":\"home\",\"junk\":\"" + "x".repeat(500) + "\"}");

        ModeRequests.ModeRequest r = requests.create("away");

        JsonNode n = json.readTree(Files.readString(root.resolve(ModeRequests.FILE)));
        assertThat(n.properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "mode", "requested_at", "expires_at");
        assertThat(UUID.fromString(n.get("id").asString()).toString()).isEqualTo(r.id());
        assertThat(n.get("mode").asString()).isEqualTo("away");
        OffsetDateTime requested = OffsetDateTime.parse(n.get("requested_at").asString());
        OffsetDateTime expires = OffsetDateTime.parse(n.get("expires_at").asString());
        assertThat(requested.toInstant()).isEqualTo(T0);
        assertThat(Duration.between(requested, expires)).isEqualTo(Duration.ofMinutes(5));
        assertThat(n.get("requested_at").asString()).endsWith("+00:00"); // ISO-8601 with an offset
        // Written next to the target and moved into place: nothing else is left in the root.
        try (Stream<Path> list = Files.list(root)) {
            assertThat(list.map(p -> p.getFileName().toString()))
                    .containsExactlyInAnyOrder(ClipFiles.VOLUME_MARKER, ModeRequests.FILE);
        }
        assertThat(requests.read()).contains(r);
    }

    @Test
    void refusesOffAndUnknownModesAndAMissingVolume() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> requests.create("off"));
        assertThrows(IllegalArgumentException.class, () -> requests.create("party"));
        assertThrows(IllegalArgumentException.class, () -> requests.create(null));
        assertThat(root.resolve(ModeRequests.FILE)).doesNotExist();
        Files.delete(root.resolve(ClipFiles.VOLUME_MARKER));
        assertThrows(VolumeUnavailableException.class, () -> requests.create("home"));
        assertThat(root.resolve(ModeRequests.FILE)).doesNotExist();
    }

    @Test
    void statusPendingAppliedFailedExpired() throws Exception {
        assertThat(requests.state().request()).isNull();

        ModeRequests.ModeRequest r = requests.create("away");
        assertThat(requests.state().request().status()).isEqualTo("pending");
        assertThat(requests.state().request().mode()).isEqualTo("away");
        assertThat(requests.state().request().requestedAt().toInstant()).isEqualTo(T0);

        // A result for another request says nothing about this one.
        heartbeat("{\"mode\":\"home\",\"mode_request\":{\"id\":\"other\",\"status\":\"applied\",\"error\":null,\"at\":\"2026-11-21T10:00:01+00:00\"}}");
        assertThat(requests.state().request().status()).isEqualTo("pending");
        assertThat(requests.state().current()).isEqualTo("home");

        heartbeat("{\"mode\":\"away\",\"mode_request\":{\"id\":\"" + r.id() + "\",\"status\":\"applied\",\"error\":null,\"at\":\"2026-11-21T10:00:30+00:00\"}}");
        assertThat(requests.state().request().status()).isEqualTo("applied");
        assertThat(requests.state().request().error()).isNull();
        assertThat(requests.state().current()).isEqualTo("away");

        heartbeat("{\"mode\":\"home\",\"mode_request\":{\"id\":\"" + r.id() + "\",\"status\":\"failed\",\"error\":\"hub refused\",\"at\":\"2026-11-21T10:00:30+00:00\"}}");
        assertThat(requests.state().request().status()).isEqualTo("failed");
        assertThat(requests.state().request().error()).isEqualTo("hub refused");

        // An unknown status in the heartbeat is not a result.
        heartbeat("{\"mode\":\"home\",\"mode_request\":{\"id\":\"" + r.id() + "\",\"status\":\"weird\"}}");
        assertThat(requests.state().request().status()).isEqualTo("pending");

        clock.advance(Duration.ofMinutes(6));
        assertThat(requests.state().request().status()).isEqualTo("pending"); // at expires_at + grace: not yet past
        clock.advance(Duration.ofSeconds(1));
        assertThat(requests.state().request().status()).isEqualTo("expired");
        heartbeat("{\"mode\":\"home\"}");
        assertThat(requests.state().request().status()).isEqualTo("expired");

        // A result the Mac mini reported still wins after expiry.
        heartbeat("{\"mode\":\"away\",\"mode_request\":{\"id\":\"" + r.id() + "\",\"status\":\"applied\"}}");
        assertThat(requests.state().request().status()).isEqualTo("applied");
    }

    @Test
    void expiredOnlyAfterAGraceSoALateResultCanArrive() throws Exception {
        // Picked up just before expires_at: the switch and its heartbeat land a little after it.
        ModeRequests.ModeRequest r = requests.create("away");
        clock.advance(Duration.ofMinutes(5).plusSeconds(45));
        assertThat(requests.state().request().status()).isEqualTo("pending");
        heartbeat("{\"mode\":\"away\",\"mode_request\":{\"id\":\"" + r.id() + "\",\"status\":\"applied\",\"error\":null,\"at\":\"2026-11-21T10:05:40+00:00\"}}");
        assertThat(requests.state().request().status()).isEqualTo("applied");

        // Never picked up: expired once the grace is over.
        requests.create("home");
        clock.advance(Duration.ofMinutes(6));
        assertThat(requests.state().request().status()).isEqualTo("pending");
        clock.advance(Duration.ofSeconds(1));
        assertThat(requests.state().request().status()).isEqualTo("expired");
    }

    @Test
    void newRequestReplacesOld() throws Exception {
        ModeRequests.ModeRequest first = requests.create("home");
        heartbeat("{\"mode\":\"home\",\"mode_request\":{\"id\":\"" + first.id() + "\",\"status\":\"applied\"}}");
        clock.advance(Duration.ofSeconds(10));

        ModeRequests.ModeRequest second = requests.create("away");

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(requests.read()).contains(second);
        ModeRequests.RequestStatus s = requests.state().request();
        assertThat(s.id()).isEqualTo(second.id());
        assertThat(s.mode()).isEqualTo("away");
        assertThat(s.status()).isEqualTo("pending");
        assertThat(second.expiresAt().toInstant()).isEqualTo(T0.plusSeconds(10).plus(Duration.ofMinutes(5)));
    }

    @Test
    void unreadableRequestFileIsNoRequest() throws Exception {
        Files.writeString(root.resolve(ModeRequests.FILE), "not json");
        assertThat(requests.read()).isEmpty();
        assertThat(requests.state().request()).isNull();
    }
}

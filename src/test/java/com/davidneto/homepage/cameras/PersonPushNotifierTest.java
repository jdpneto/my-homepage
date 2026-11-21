package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest
@ActiveProfiles("test")
class PersonPushNotifierTest {

    static final String SUMMARY_CLICK = "https://davidneto.eu/admin/cameras?trigger=person";

    record Sent(String title, String message, String priority, String tags, String click) {
    }

    @Autowired CameraSettingRepository settings;
    AlertCheckerTest.MutableClock clock;
    Instant t0;
    List<Sent> sent;
    boolean accept;
    RuntimeException failWith;
    Notifier notifier;
    AlertMute mute;
    CameraProperties props;
    PersonPushNotifier push;

    @BeforeEach
    void setUp() {
        settings.deleteAll();
        t0 = Instant.parse("2026-11-21T14:00:00Z");
        clock = new AlertCheckerTest.MutableClock(t0);
        sent = new ArrayList<>();
        accept = true;
        failWith = null;
        notifier = new Notifier() {
            @Override
            public boolean send(String title, String message, String priority) {
                return send(title, message, priority, null, null);
            }

            @Override
            public boolean send(String title, String message, String priority, String tags, String clickUrl) {
                if (failWith != null) throw failWith;
                sent.add(new Sent(title, message, priority, tags, clickUrl));
                return accept;
            }
        };
        props = new CameraProperties();
        mute = new AlertMute(settings, clock);
        push = restart();
    }

    PersonPushNotifier restart() {
        return new PersonPushNotifier(notifier, settings, mute, props, clock);
    }

    /** A clip that started {@code ago} before now, in the cameras' own +01:00 offset. */
    ClipSidecar clip(String camera, Duration ago, String mode, String... triggers) {
        OffsetDateTime start = clock.instant().minus(ago).atOffset(ZoneOffset.ofHours(1));
        return new ClipSidecar(camera, "id-" + camera, 0, start, start.plusSeconds(10), 10,
                Arrays.asList(triggers), mode);
    }

    void index(ClipSidecar s) {
        push.clipAdded(s, clock.instant());
    }

    void at(Duration sinceStart) {
        clock.now = t0.plus(sinceStart);
    }

    static Duration min(long m) {
        return Duration.ofMinutes(m);
    }

    static Sent person(String title, String click) {
        return new Sent(title, "Away mode", "high", "bust_in_silhouette", click);
    }

    static Sent summary(String title, String body) {
        return new Sent(title, body, "high", "bust_in_silhouette", SUMMARY_CLICK);
    }

    @Test
    void firstAwayPersonClipPushesNow() {
        index(clip("Garage", min(2), "away", "person"));
        assertThat(sent).containsExactly(person("Person · Garage · 14:58",
                "https://davidneto.eu/admin/cameras?camera=Garage&trigger=person"));

        // camera names are URL-encoded in the tap target (a space must not become '+')
        settings.deleteAll();
        push = restart();
        sent.clear();
        index(clip("Living Room", min(0), "away", "motion", "person"));
        assertThat(sent).containsExactly(person("Person · Living Room · 15:00",
                "https://davidneto.eu/admin/cameras?camera=Living%20Room&trigger=person"));
    }

    @Test
    void homeOrNullModeNeverPushes() {
        for (String mode : new String[]{"home", "disarm", "off", "", null}) {
            index(clip("Garage", min(1), mode, "person"));
        }
        assertThat(sent).isEmpty();
        at(min(60));
        push.flush();
        assertThat(sent).isEmpty(); // not counted either
    }

    @Test
    void motionOnlyNeverPushes() {
        index(clip("Garage", min(1), "away", "motion"));
        index(clip("Backyard", min(1), "away", "animal"));
        index(clip("Backyard", min(1), "away"));
        assertThat(sent).isEmpty();
        at(min(60));
        push.flush();
        assertThat(sent).isEmpty();
    }

    @Test
    void oldClipsNeverPush() {
        index(clip("Garage", min(31), "away", "person"));
        assertThat(sent).isEmpty();
        at(min(60));
        push.flush();
        assertThat(sent).isEmpty(); // an old clip is not counted for the summary either

        index(clip("Garage", min(29), "away", "person"));
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).title()).startsWith("Person · Garage · ");
    }

    @Test
    void secondClipInTheHourIsCounted() {
        index(clip("Garage", min(0), "away", "person"));
        index(clip("Backyard", min(0), "away", "person")); // same minute, another camera
        assertThat(sent).extracting(Sent::title).containsExactly("Person · Garage · 15:00");
    }

    @Test
    void summaryAfterTheHour() {
        index(clip("Garage", min(0), "away", "person"));
        at(min(10));
        index(clip("Garage", min(0), "away", "person"));
        at(min(20));
        index(clip("Backyard", min(0), "away", "person"));
        assertThat(sent).hasSize(1);

        at(min(59));
        push.flush();
        assertThat(sent).hasSize(1);

        at(min(60));
        push.flush();
        assertThat(sent).hasSize(2);
        // equal counts: by name
        assertThat(sent.get(1)).isEqualTo(summary("2 more person clips while away", "Backyard 1, Garage 1"));

        // the summary starts the next hour: a clip right after it is counted, not pushed
        at(min(61));
        index(clip("Garage", min(0), "away", "person"));
        at(min(62));
        index(clip("Garage", min(0), "away", "person"));
        at(min(63));
        index(clip("Backyard", min(0), "away", "person"));
        at(min(119));
        push.flush();
        assertThat(sent).hasSize(2);
        at(min(120));
        push.flush();
        assertThat(sent.get(2)).isEqualTo(summary("3 more person clips while away", "Garage 2, Backyard 1"));

        at(min(121));
        index(clip("Garage", min(0), "away", "person"));
        at(min(180));
        push.flush();
        assertThat(sent.get(3)).isEqualTo(summary("1 more person clip while away", "Garage 1"));
        assertThat(sent).hasSize(4);
    }

    @Test
    void lateClipAfterTheHourJoinsThePendingSummary() {
        index(clip("Garage", min(0), "away", "person"));
        at(min(10));
        index(clip("Backyard", min(0), "away", "person"));
        // the hour is over but the minute job has not run yet
        at(min(60).plusSeconds(30));
        index(clip("Garage", min(0), "away", "person"));
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(summary("2 more person clips while away", "Backyard 1, Garage 1"));
        at(min(61));
        push.flush();
        assertThat(sent).hasSize(2);
    }

    @Test
    void noSummaryWhenNothingPending() {
        push.flush(); // no state at all
        index(clip("Garage", min(0), "away", "person"));
        at(min(60));
        push.flush();
        at(min(120));
        push.flush();
        assertThat(sent).hasSize(1);
    }

    @Test
    void mutedSendsNothingAndDropsPending() {
        index(clip("Garage", min(0), "away", "person"));
        at(min(10));
        index(clip("Backyard", min(0), "away", "person")); // pending
        mute.set(true, null);
        at(min(20));
        index(clip("Garage", min(0), "away", "person"));
        at(min(60));
        push.flush();
        assertThat(sent).hasSize(1);

        mute.set(false, null);
        at(min(61));
        push.flush();
        assertThat(sent).hasSize(1); // what was seen before/while muted is not summarised later

        at(min(62));
        index(clip("Garage", min(0), "away", "person")); // hour is over, nothing pending: push now
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).title()).startsWith("Person · Garage · ");

        // "Mute 8 h" too
        mute.set(true, clock.instant().plus(Duration.ofHours(8)));
        at(min(200));
        index(clip("Garage", min(0), "away", "person"));
        push.flush();
        assertThat(sent).hasSize(2);
    }

    @Test
    void stateSurvivesRestart() {
        index(clip("Garage", min(0), "away", "person"));
        at(min(5));
        index(clip("Backyard", min(0), "away", "person"));

        push = restart(); // deploy in the middle of the hour
        at(min(10));
        index(clip("Garage", min(0), "away", "person"));
        assertThat(sent).hasSize(1); // no second immediate push

        at(min(60));
        push.flush();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1)).isEqualTo(summary("2 more person clips while away", "Backyard 1, Garage 1"));
        // one small row
        assertThat(settings.findAll()).filteredOn(s -> s.getName().startsWith("person_push"))
                .singleElement().satisfies(s -> assertThat(s.getValue().length()).isLessThanOrEqualTo(500));
    }

    @Test
    void failedSendIsRetriedByFlush() {
        accept = false;
        index(clip("Garage", min(0), "away", "person"));
        assertThat(sent).hasSize(1); // attempted, not delivered

        at(min(1));
        push.flush(); // still failing: kept
        assertThat(sent).hasSize(2);

        accept = true;
        at(min(2));
        push.flush();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(2)).isEqualTo(summary("1 more person clip while away", "Garage 1"));

        at(min(3));
        index(clip("Garage", min(0), "away", "person")); // the delivered summary started the hour
        assertThat(sent).hasSize(3);
    }

    @Test
    void notifierFailureNeverThrows() {
        failWith = new IllegalStateException("boom");
        assertThatCode(() -> index(clip("Garage", min(0), "away", "person"))).doesNotThrowAnyException();
        at(min(1));
        assertThatCode(() -> push.flush()).doesNotThrowAnyException();
        assertThatCode(() -> push.clipAdded(null, clock.instant())).doesNotThrowAnyException();
    }

    @Test
    void manyCamerasStillFitTheSettingRow() {
        index(clip("Garage", min(0), "away", "person"));
        for (int i = 0; i < 40; i++) {
            index(clip("A rather long camera name number " + i, min(0), "away", "person"));
        }
        assertThat(settings.findAll()).filteredOn(s -> s.getName().startsWith("person_push"))
                .singleElement().satisfies(s -> assertThat(s.getValue().length()).isLessThanOrEqualTo(500));
        at(min(60));
        push.flush();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).title()).isEqualTo("40 more person clips while away");
    }
}

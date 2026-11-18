package com.davidneto.homepage.cameras;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Camera jobs must not queue behind the gallery drop scanner on a single scheduler thread. */
@SpringBootTest
@ActiveProfiles("test")
class SchedulingConfigIT {

    @Autowired ThreadPoolTaskScheduler taskScheduler;

    @Test
    void schedulerHasSeveralThreads() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isGreaterThanOrEqualTo(4);
    }
}

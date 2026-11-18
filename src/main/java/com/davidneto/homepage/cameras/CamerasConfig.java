package com.davidneto.homepage.cameras;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(CameraProperties.class)
public class CamerasConfig {

    /**
     * Several scheduler threads: the camera indexer, cleanup and alert checks must not queue
     * behind the gallery drop scanner (which runs ffmpeg) on Spring's default single thread.
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }
}

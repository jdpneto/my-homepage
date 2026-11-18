package com.davidneto.homepage.cameras;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CameraProperties.class)
public class CamerasConfig {
}

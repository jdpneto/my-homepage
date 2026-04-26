package com.davidneto.homepage.gallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.gallery")
public class GalleryProperties {

    private String rootDir = "./gallery";
    private Drop drop = new Drop();

    public String getRootDir() { return rootDir; }
    public void setRootDir(String rootDir) { this.rootDir = rootDir; }
    public Drop getDrop() { return drop; }
    public void setDrop(Drop drop) { this.drop = drop; }

    public static class Drop {
        private String username = "mae-drop";
        private String password = "";
        private int scanIntervalSeconds = 30;
        private int stableAfterSeconds = 10;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getScanIntervalSeconds() { return scanIntervalSeconds; }
        public void setScanIntervalSeconds(int v) { this.scanIntervalSeconds = v; }
        public int getStableAfterSeconds() { return stableAfterSeconds; }
        public void setStableAfterSeconds(int v) { this.stableAfterSeconds = v; }
    }
}

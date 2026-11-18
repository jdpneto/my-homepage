package com.davidneto.homepage.cameras;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class FileStoreDiskSpace implements DiskSpace {
    @Override
    public long total(Path path) {
        try {
            return Files.getFileStore(path).getTotalSpace();
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public long free(Path path) {
        try {
            return Files.getFileStore(path).getUsableSpace();
        } catch (IOException e) {
            return 0;
        }
    }
}

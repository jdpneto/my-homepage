package com.davidneto.homepage.cameras;

import java.nio.file.Path;

/** Size and free space of the filesystem holding a path. */
public interface DiskSpace {
    long total(Path path);

    long free(Path path);
}

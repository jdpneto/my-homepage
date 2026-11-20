package com.davidneto.homepage.cameras;

/** The camera volume (or its marker file) is missing: deleting now would only drop rows, not files. */
public class VolumeUnavailableException extends RuntimeException {
    public VolumeUnavailableException() {
        super("camera volume not available — nothing was deleted");
    }
}

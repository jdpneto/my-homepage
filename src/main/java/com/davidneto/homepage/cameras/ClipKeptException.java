package com.davidneto.homepage.cameras;

/** A kept clip can only be deleted after it has been unkept. */
public class ClipKeptException extends IllegalStateException {
    public ClipKeptException() {
        super("clip is kept - unkeep it first");
    }
}

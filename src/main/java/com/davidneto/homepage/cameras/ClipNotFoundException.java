package com.davidneto.homepage.cameras;

public class ClipNotFoundException extends RuntimeException {
    public ClipNotFoundException(long id) {
        super("no clip " + id);
    }
}

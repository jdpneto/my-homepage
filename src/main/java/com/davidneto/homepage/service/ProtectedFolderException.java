package com.davidneto.homepage.service;

/** A WebDAV user's folder is on the camera volume and must not be wiped. */
public class ProtectedFolderException extends IllegalStateException {
    public ProtectedFolderException(String username) {
        super("'" + username + "' is on the camera volume and is protected: its files cannot be deleted from here");
    }
}

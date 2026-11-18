package com.davidneto.homepage.cameras;

/** Sends a push to David's phones. Never throws; returns whether it was sent. */
@FunctionalInterface
public interface Notifier {
    boolean send(String title, String message, String priority);
}

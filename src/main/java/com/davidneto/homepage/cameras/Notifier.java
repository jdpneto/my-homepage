package com.davidneto.homepage.cameras;

/** Sends a push to David's phones. Never throws; returns whether it was sent. */
@FunctionalInterface
public interface Notifier {
    boolean send(String title, String message, String priority);

    /** With ntfy tags and a URL opened on tap; implementations without them just send the text. */
    default boolean send(String title, String message, String priority, String tags, String clickUrl) {
        return send(title, message, priority);
    }
}

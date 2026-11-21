package com.davidneto.homepage.cameras;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {CameraApiController.class, CameraAlertsController.class, CameraModeController.class})
public class CameraApiErrors {

    @ExceptionHandler(ClipNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ClipNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ClipCursor.BadCursorException.class)
    public ResponseEntity<Map<String, String>> badCursor(ClipCursor.BadCursorException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ClipKeptException.class)
    public ResponseEntity<Map<String, String>> kept(ClipKeptException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VolumeUnavailableException.class)
    public ResponseEntity<Map<String, String>> volume(VolumeUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}

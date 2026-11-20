-- Small key/value settings for the camera feature (e.g. alerts mute).
CREATE TABLE camera_setting (
    name   VARCHAR(64)  PRIMARY KEY,
    value  VARCHAR(500) NOT NULL
);

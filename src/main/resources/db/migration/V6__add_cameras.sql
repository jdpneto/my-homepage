-- Camera clip browser (part 2). Files on the camera volume are the source of
-- truth; this table is a rebuildable index (the keep flag mirrors <stem>.keep).
CREATE TABLE camera_clip (
    id                  BIGSERIAL                PRIMARY KEY,
    stem                VARCHAR(512)             NOT NULL UNIQUE,
    camera_name         VARCHAR(200)             NOT NULL,
    camera_id           VARCHAR(100)             NOT NULL,
    channel             INTEGER                  NOT NULL,
    start_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    utc_offset_seconds  INTEGER                  NOT NULL,
    end_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_seconds    INTEGER                  NOT NULL,
    triggers            VARCHAR(255)             NOT NULL,
    size_bytes          BIGINT                   NOT NULL,
    kept                BOOLEAN                  NOT NULL DEFAULT FALSE,
    kept_at             TIMESTAMP WITH TIME ZONE,
    thumb_path          VARCHAR(512),
    indexed_at          TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_camera_clip_start ON camera_clip (start_at DESC, id DESC);
CREATE INDEX idx_camera_clip_camera_start ON camera_clip (camera_name, start_at DESC);

CREATE TABLE camera_token (
    id            BIGSERIAL                PRIMARY KEY,
    token_hash    VARCHAR(64)                 NOT NULL UNIQUE,
    label         VARCHAR(100),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at  TIMESTAMP WITH TIME ZONE,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE camera_alert_state (
    alert_key         VARCHAR(64)              PRIMARY KEY,
    active            BOOLEAN                  NOT NULL,
    since             TIMESTAMP WITH TIME ZONE,
    last_notified_at  TIMESTAMP WITH TIME ZONE,
    detail            VARCHAR(500)
);

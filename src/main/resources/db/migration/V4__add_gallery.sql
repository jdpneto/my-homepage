CREATE TABLE gallery_item (
    id                  BIGSERIAL    PRIMARY KEY,
    media_kind          VARCHAR(10)  NOT NULL,
    storage_key         UUID         NOT NULL UNIQUE,
    original_filename   VARCHAR(512) NOT NULL,
    content_type        VARCHAR(100) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    content_hash        VARCHAR(64)  NOT NULL UNIQUE,
    width               INTEGER,
    height              INTEGER,
    duration_seconds    INTEGER,
    taken_at            TIMESTAMP,
    bucket_year         INTEGER      NOT NULL,
    bucket_month        INTEGER      NOT NULL,
    bucket_source       VARCHAR(10)  NOT NULL,
    uploaded_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    uploader_name       VARCHAR(100),
    caption             TEXT,
    caption_updated_at  TIMESTAMP,
    caption_updated_by  VARCHAR(100)
);

CREATE INDEX idx_gallery_bucket ON gallery_item (bucket_year DESC, bucket_month DESC);
CREATE INDEX idx_gallery_recent ON gallery_item (uploaded_at DESC);

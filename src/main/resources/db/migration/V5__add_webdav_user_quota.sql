ALTER TABLE webdav_users
    ADD COLUMN storage_quota_mb BIGINT NOT NULL DEFAULT 50;

-- "Remember this device" for the admin login: one row per remembered browser.
-- Only the SHA-256 (hex) of the token is stored; the cookie carries series:token.
CREATE TABLE admin_remember_token (
    series        VARCHAR(64)              PRIMARY KEY,
    token_hash    VARCHAR(64)              NOT NULL,
    username      VARCHAR(100)             NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_admin_remember_token_last_used ON admin_remember_token (last_used_at);

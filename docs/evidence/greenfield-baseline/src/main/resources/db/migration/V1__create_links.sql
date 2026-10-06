CREATE TABLE links (
    code VARCHAR(32) PRIMARY KEY,
    destination_url VARCHAR(4096) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT valid_expiry CHECK (expires_at IS NULL OR expires_at > created_at)
);
CREATE TABLE link_metrics (
    code VARCHAR(32) PRIMARY KEY REFERENCES links(code),
    total_redirects BIGINT NOT NULL DEFAULT 0 CHECK (total_redirects >= 0),
    last_accessed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE idempotency_records (
    key_hash CHAR(64) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    code VARCHAR(32) NOT NULL REFERENCES links(code),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX links_created_at_idx ON links(created_at);

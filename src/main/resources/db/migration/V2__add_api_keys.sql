CREATE TABLE api_keys (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_value  VARCHAR(64) NOT NULL UNIQUE,
    name       VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_api_keys_key_value ON api_keys(key_value);

INSERT INTO api_keys (key_value, name, created_at, active)
VALUES ('test-key-12345', 'Test Key', CURRENT_TIMESTAMP, TRUE);

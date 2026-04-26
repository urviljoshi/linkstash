ALTER TABLE links ADD COLUMN expires_at TIMESTAMP NULL;
CREATE INDEX idx_links_expires_at ON links(expires_at);

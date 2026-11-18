-- Candidate only: do not apply before explicit approval. No business data is inserted.
CREATE TABLE idempotency_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_scope VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  method VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  route VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  http_status SMALLINT UNSIGNED NULL,
  encrypted_response MEDIUMBLOB NULL,
  lease_until DATETIME(3) NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  UNIQUE KEY uk_idempotency_scope (actor_scope, method, route, key_hash),
  INDEX ix_idempotency_expiry (state, expires_at),
  CONSTRAINT ck_idempotency_method CHECK (method IN ('POST','PUT','PATCH','DELETE')),
  CONSTRAINT ck_idempotency_result CHECK (
    (state = 'IN_PROGRESS' AND http_status IS NULL AND encrypted_response IS NULL)
    OR (state = 'COMPLETED' AND http_status BETWEEN 100 AND 599 AND encrypted_response IS NOT NULL)
  ),
  CONSTRAINT ck_idempotency_time CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE audit_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resource_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resource_id BIGINT UNSIGNED NOT NULL,
  masked_before_after JSON NOT NULL,
  result VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  trace_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  INDEX ix_audit_resource (resource_type, resource_id, id),
  INDEX ix_audit_actor_created (actor_id, created_at),
  CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_audit_result CHECK (result IN ('SUCCESS','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

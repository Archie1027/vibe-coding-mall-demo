-- M1-D persistent quote foundation: ONE EMPTY table, no quote/stock seed.
-- Apply only after separate approval through the hash-bound migration runner.
CREATE TABLE checkout_quote (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  snapshot_json JSON NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  used_order_id BIGINT UNSIGNED NULL,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  UNIQUE KEY uk_checkout_quote_used_order (used_order_id),
  KEY ix_checkout_quote_customer_expiry (customer_id,expires_at,id),
  KEY ix_checkout_quote_expiry (expires_at,id),
  CONSTRAINT fk_checkout_quote_customer FOREIGN KEY (customer_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_checkout_quote_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_checkout_quote_expiry CHECK (expires_at > created_at),
  CONSTRAINT ck_checkout_quote_used_order CHECK (used_order_id IS NULL OR used_order_id > 0),
  CONSTRAINT ck_checkout_quote_snapshot CHECK (JSON_TYPE(snapshot_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

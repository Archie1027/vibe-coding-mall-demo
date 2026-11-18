-- Candidate only. Applying this empty-table migration requires separate approval.
CREATE TABLE payment_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  payment_id BIGINT UNSIGNED NOT NULL,
  channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  handled_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_payment_event_identity (channel,event_id,event_type),
  KEY ix_payment_event_payment (payment_id,id),
  CONSTRAINT fk_payment_event_payment FOREIGN KEY (payment_id) REFERENCES payment(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_payment_event_mock_success CHECK (channel='MOCK' AND event_type='PAYMENT_SUCCESS'),
  CONSTRAINT ck_payment_event_id CHECK (CHAR_LENGTH(event_id) BETWEEN 1 AND 100),
  CONSTRAINT ck_payment_event_hash CHECK (payload_hash REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Candidate: one empty local payment facts table. Separate approval required before applying.
-- Does not enable channels, callback routes, payment creation, finance, outbox or seeds.
CREATE TABLE payment (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT UNSIGNED NOT NULL,
  payment_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount_minor BIGINT UNSIGNED NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel_transaction_id VARCHAR(100) COLLATE utf8mb4_bin NULL,
  active_order_id BIGINT UNSIGNED GENERATED ALWAYS AS
    (CASE WHEN state IN ('CREATED','PROCESSING') THEN order_id ELSE NULL END) STORED,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_payment_no UNIQUE (payment_no),
  CONSTRAINT uk_payment_channel_transaction UNIQUE (channel,channel_transaction_id),
  CONSTRAINT uk_payment_active_order UNIQUE (active_order_id),
  KEY ix_payment_order_state (order_id,state,id),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES sales_order(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_payment_no CHECK (CHAR_LENGTH(payment_no) BETWEEN 1 AND 40),
  CONSTRAINT ck_payment_channel CHECK (channel IN ('MOCK','SANDBOX')),
  CONSTRAINT ck_payment_state CHECK (state IN ('CREATED','PROCESSING','SUCCEEDED','FAILED','CLOSED')),
  CONSTRAINT ck_payment_amount CHECK (amount_minor BETWEEN 1 AND 999999999999999999),
  CONSTRAINT ck_payment_currency CHECK (currency='CNY'),
  CONSTRAINT ck_payment_transaction CHECK (channel_transaction_id IS NULL OR CHAR_LENGTH(channel_transaction_id) BETWEEN 1 AND 100),
  CONSTRAINT ck_payment_success CHECK (state<>'SUCCEEDED' OR channel_transaction_id IS NOT NULL),
  CONSTRAINT ck_payment_version CHECK (version BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

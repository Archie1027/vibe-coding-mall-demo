-- M1-D stock foundation: three EMPTY tables only; no stock seed or order writes.
-- Execute through the approval-gated migration runner; never rerun V1-V5.
CREATE TABLE stock_balance (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  on_hand INT NOT NULL DEFAULT 0,
  reserved INT NOT NULL DEFAULT 0,
  quarantine INT NOT NULL DEFAULT 0,
  warning_threshold INT NOT NULL DEFAULT 5,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_stock_balance_warehouse_sku UNIQUE (warehouse_id,sku_id),
  CONSTRAINT fk_stock_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_stock_balance_sku FOREIGN KEY (sku_id) REFERENCES sku(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_stock_balance_quantities CHECK (on_hand >= reserved AND reserved >= 0 AND quarantine >= 0),
  CONSTRAINT ck_stock_balance_threshold CHECK (warning_threshold >= 0),
  CONSTRAINT ck_stock_balance_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_reservation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  business_line_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  quantity INT NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_stock_reservation_business_line UNIQUE (business_type,business_line_id),
  KEY ix_stock_reservation_warehouse_sku_state (warehouse_id,sku_id,state,id),
  CONSTRAINT fk_stock_reservation_balance FOREIGN KEY (warehouse_id,sku_id) REFERENCES stock_balance(warehouse_id,sku_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_stock_reservation_business CHECK (business_type IN ('ORDER','TRANSFER') AND business_line_id > 0),
  CONSTRAINT ck_stock_reservation_quantity CHECK (quantity BETWEEN 1 AND 1000000),
  CONSTRAINT ck_stock_reservation_state CHECK (state IN ('HELD','CONSUMED','RELEASED')),
  CONSTRAINT ck_stock_reservation_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_ledger (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  biz_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  biz_id BIGINT UNSIGNED NOT NULL,
  biz_line_id BIGINT UNSIGNED NOT NULL,
  action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  on_hand_before INT NOT NULL,
  reserved_before INT NOT NULL,
  quarantine_before INT NOT NULL,
  in_transit_before BIGINT NOT NULL DEFAULT 0,
  delta_on_hand INT NOT NULL,
  delta_reserved INT NOT NULL,
  delta_quarantine INT NOT NULL,
  delta_in_transit BIGINT NOT NULL DEFAULT 0,
  on_hand_after INT NOT NULL,
  reserved_after INT NOT NULL,
  quarantine_after INT NOT NULL,
  in_transit_after BIGINT NOT NULL DEFAULT 0,
  actor_id BIGINT UNSIGNED NULL,
  trace_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_stock_ledger_business_action UNIQUE (biz_type,biz_line_id,action,warehouse_id,sku_id),
  KEY ix_stock_ledger_warehouse_sku (warehouse_id,sku_id,id),
  KEY ix_stock_ledger_business (biz_type,biz_id,id),
  CONSTRAINT fk_stock_ledger_balance FOREIGN KEY (warehouse_id,sku_id) REFERENCES stock_balance(warehouse_id,sku_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_stock_ledger_actor FOREIGN KEY (actor_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_stock_ledger_business CHECK (biz_id > 0 AND biz_line_id > 0 AND CHAR_LENGTH(biz_type) > 0 AND CHAR_LENGTH(action) > 0 AND CHAR_LENGTH(trace_id) > 0),
  CONSTRAINT ck_stock_ledger_before CHECK (on_hand_before >= reserved_before AND reserved_before >= 0 AND quarantine_before >= 0 AND in_transit_before >= 0),
  CONSTRAINT ck_stock_ledger_after CHECK (on_hand_after >= reserved_after AND reserved_after >= 0 AND quarantine_after >= 0 AND in_transit_after >= 0),
  CONSTRAINT ck_stock_ledger_deltas CHECK (
    on_hand_after = on_hand_before + delta_on_hand AND
    reserved_after = reserved_before + delta_reserved AND
    quarantine_after = quarantine_before + delta_quarantine AND
    in_transit_after = in_transit_before + delta_in_transit AND
    (delta_on_hand <> 0 OR delta_reserved <> 0 OR delta_quarantine <> 0 OR delta_in_transit <> 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

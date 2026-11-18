-- Candidate M1-D order foundation. Apply only after separate database approval.
-- Two empty tables; no orders, stock, coupon, payment, or shipment seed data.
CREATE TABLE sales_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quote_id BIGINT UNSIGNED NOT NULL,
  customer_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  address_snapshot_json JSON NOT NULL,
  coupon_id BIGINT UNSIGNED NULL,
  remark VARCHAR(200) NULL,
  gross_minor BIGINT UNSIGNED NOT NULL,
  discount_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  shipping_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  payable_minor BIGINT UNSIGNED NOT NULL,
  refunded_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  expires_at DATETIME(3) NOT NULL,
  paid_at DATETIME(3) NULL,
  completed_at DATETIME(3) NULL,
  cancelled_at DATETIME(3) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_sales_order_no UNIQUE (order_no),
  CONSTRAINT uk_sales_order_quote UNIQUE (quote_id),
  KEY ix_sales_order_customer_created (customer_id,created_at,id),
  KEY ix_sales_order_state_expiry (state,expires_at,id),
  KEY ix_sales_order_warehouse_state (warehouse_id,state,id),
  CONSTRAINT fk_sales_order_quote FOREIGN KEY (quote_id) REFERENCES checkout_quote(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_sales_order_customer FOREIGN KEY (customer_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_sales_order_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_sales_order_no CHECK (CHAR_LENGTH(order_no) BETWEEN 1 AND 40),
  CONSTRAINT ck_sales_order_state CHECK (state IN ('UNPAID','CLOSING','CANCELLED','PAID','SHIPPED','COMPLETED')),
  CONSTRAINT ck_sales_order_address CHECK (JSON_TYPE(address_snapshot_json) = 'OBJECT'),
  CONSTRAINT ck_sales_order_coupon CHECK (coupon_id IS NULL OR coupon_id > 0),
  CONSTRAINT ck_sales_order_amounts CHECK (gross_minor BETWEEN 1 AND 999999999999999999
    AND discount_minor < gross_minor AND shipping_minor <= 999999999999999999
    AND payable_minor BETWEEN 1 AND 999999999999999999
    AND payable_minor = gross_minor - discount_minor + shipping_minor
    AND refunded_minor <= payable_minor),
  CONSTRAINT ck_sales_order_version CHECK (version BETWEEN 0 AND 9007199254740991),
  CONSTRAINT ck_sales_order_expiry CHECK (expires_at > created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sales_order_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT UNSIGNED NOT NULL,
  line_no INT NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  product_id BIGINT UNSIGNED NOT NULL,
  product_name VARCHAR(120) NOT NULL,
  specs_snapshot_json JSON NOT NULL,
  image_snapshot_json JSON NULL,
  quantity INT NOT NULL,
  unit_price_minor BIGINT UNSIGNED NOT NULL,
  gross_minor BIGINT UNSIGNED NOT NULL,
  discount_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  payable_minor BIGINT UNSIGNED NOT NULL,
  returned_quantity INT NOT NULL DEFAULT 0,
  refund_quantity_reserved INT NOT NULL DEFAULT 0,
  refunded_minor BIGINT UNSIGNED NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_sales_order_item_line UNIQUE (order_id,line_no),
  CONSTRAINT uk_sales_order_item_sku UNIQUE (order_id,sku_id),
  KEY ix_sales_order_item_sku (sku_id,id),
  CONSTRAINT fk_sales_order_item_order FOREIGN KEY (order_id) REFERENCES sales_order(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_sales_order_item_sku FOREIGN KEY (sku_id) REFERENCES sku(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_sales_order_item_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_sales_order_item_line CHECK (line_no BETWEEN 0 AND 99),
  CONSTRAINT ck_sales_order_item_specs CHECK (JSON_TYPE(specs_snapshot_json) = 'ARRAY'),
  CONSTRAINT ck_sales_order_item_image CHECK (image_snapshot_json IS NULL OR JSON_TYPE(image_snapshot_json) = 'OBJECT'),
  CONSTRAINT ck_sales_order_item_quantity CHECK (quantity BETWEEN 1 AND 99
    AND returned_quantity >= 0 AND refund_quantity_reserved >= 0
    AND returned_quantity + refund_quantity_reserved <= quantity),
  CONSTRAINT ck_sales_order_item_amounts CHECK (unit_price_minor BETWEEN 1 AND 999999999999999999
    AND gross_minor BETWEEN 1 AND 999999999999999999 AND discount_minor <= gross_minor
    AND gross_minor = CAST(unit_price_minor AS DECIMAL(38,0)) * quantity
    AND payable_minor = gross_minor - discount_minor
    AND refunded_minor <= payable_minor),
  CONSTRAINT ck_sales_order_item_version CHECK (version BETWEEN 0 AND 9007199254740991)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

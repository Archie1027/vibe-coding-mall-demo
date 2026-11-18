-- Candidate M1-C migration; do not execute without separate approval. No account/catalog changes or business seed data.
-- All timestamps use UTC expressions. The application serializes each customer's writes on their enabled account row.
CREATE TABLE customer_address (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  recipient VARCHAR(50) NOT NULL,
  phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  province VARCHAR(120) NOT NULL,
  city VARCHAR(120) NOT NULL,
  district VARCHAR(120) NOT NULL,
  detail VARCHAR(200) NOT NULL,
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  default_owner BIGINT UNSIGNED GENERATED ALWAYS AS (IF(is_default,customer_id,NULL)) STORED,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_customer_address_default UNIQUE (default_owner),
  INDEX ix_customer_address_owner_id (customer_id,id),
  CONSTRAINT fk_customer_address_owner FOREIGN KEY (customer_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_customer_address_default CHECK (is_default IN (0,1)),
  CONSTRAINT ck_customer_address_version CHECK (version >= 0 AND version <= 9007199254740991),
  CONSTRAINT ck_customer_address_recipient CHECK (CHAR_LENGTH(TRIM(recipient)) BETWEEN 1 AND 50),
  CONSTRAINT ck_customer_address_phone CHECK (phone REGEXP '^1[0-9]{10}$'),
  CONSTRAINT ck_customer_address_province CHECK (CHAR_LENGTH(TRIM(province)) BETWEEN 1 AND 120),
  CONSTRAINT ck_customer_address_city CHECK (CHAR_LENGTH(TRIM(city)) BETWEEN 1 AND 120),
  CONSTRAINT ck_customer_address_district CHECK (CHAR_LENGTH(TRIM(district)) BETWEEN 1 AND 120),
  CONSTRAINT ck_customer_address_detail CHECK (CHAR_LENGTH(TRIM(detail)) BETWEEN 1 AND 200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cart_item (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  customer_id BIGINT UNSIGNED NOT NULL,
  sku_id BIGINT UNSIGNED NOT NULL,
  quantity INT NOT NULL,
  selected BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_cart_item_owner_sku UNIQUE (customer_id,sku_id),
  INDEX ix_cart_item_owner_id (customer_id,id),
  CONSTRAINT fk_cart_item_owner FOREIGN KEY (customer_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_cart_item_sku FOREIGN KEY (sku_id) REFERENCES sku(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_cart_item_quantity CHECK (quantity BETWEEN 1 AND 99),
  CONSTRAINT ck_cart_item_selected CHECK (selected IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

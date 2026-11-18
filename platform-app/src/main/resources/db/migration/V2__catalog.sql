-- Candidate M1 catalog migration. Apply only after separate review and approval.
CREATE TABLE category (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  parent_id BIGINT UNSIGNED NULL,
  sort_order INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  INDEX ix_category_parent_sort (parent_id, sort_order, id),
  CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_category_sort CHECK (sort_order >= 0),
  CONSTRAINT ck_category_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Parent depth and cycles require checks against multiple rows; future category writes enforce them in the service.

CREATE TABLE brand (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT ck_brand_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE asset (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  storage_key VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_type VARCHAR(100) CHARACTER SET ascii NOT NULL,
  size_bytes BIGINT UNSIGNED NOT NULL,
  visibility VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_id BIGINT UNSIGNED NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_asset_storage_key UNIQUE (storage_key),
  CONSTRAINT fk_asset_owner FOREIGN KEY (owner_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_asset_visibility CHECK (visibility IN ('PUBLIC','PRIVATE')),
  CONSTRAINT ck_asset_content_type CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
  CONSTRAINT ck_asset_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  category_id BIGINT UNSIGNED NOT NULL,
  brand_id BIGINT UNSIGNED NOT NULL,
  description TEXT NOT NULL,
  featured BOOLEAN NOT NULL DEFAULT FALSE,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  rating_sum BIGINT NOT NULL DEFAULT 0,
  rating_count BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  INDEX ix_product_state_category_id (state, category_id, id),
  INDEX ix_product_brand_id (brand_id, id),
  INDEX ix_product_featured_created (state, featured, created_at, id),
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_product_brand FOREIGN KEY (brand_id) REFERENCES brand(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_product_state CHECK (state IN ('DRAFT','ON_SALE','OFF_SALE')),
  CONSTRAINT ck_product_rating CHECK (rating_count >= 0 AND rating_sum >= 0 AND rating_sum <= 5 * rating_count),
  CONSTRAINT ck_product_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sku (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  product_id BIGINT UNSIGNED NOT NULL,
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  specs_json JSON NOT NULL,
  specs_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  price_minor BIGINT UNSIGNED NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_sku_code UNIQUE (code),
  CONSTRAINT uk_sku_product_specs UNIQUE (product_id, specs_hash),
  CONSTRAINT fk_sku_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_sku_price CHECK (price_minor BETWEEN 1 AND 999999999999999999),
  CONSTRAINT ck_sku_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_asset (
  product_id BIGINT UNSIGNED NOT NULL,
  asset_id BIGINT UNSIGNED NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (product_id, asset_id),
  CONSTRAINT uk_product_asset_sort UNIQUE (product_id, sort_order),
  CONSTRAINT fk_product_asset_product FOREIGN KEY (product_id) REFERENCES product(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_product_asset_asset FOREIGN KEY (asset_id) REFERENCES asset(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_product_asset_sort CHECK (sort_order >= 0),
  CONSTRAINT ck_product_asset_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Reviewed initial M0 identity schema. Flyway execution is opt-in (FLYWAY_ENABLED=true).
-- UTC timestamps are supplied by server expressions/application; no credentials or demo users.
CREATE TABLE account (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  username VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  password_hash VARCHAR(100) CHARACTER SET ascii NOT NULL,
  display_name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_account_type_username UNIQUE (account_type, username),
  CONSTRAINT ck_account_type CHECK (account_type IN ('CUSTOMER','STAFF')),
  CONSTRAINT ck_account_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE role (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_role_code UNIQUE (code),
  CONSTRAINT ck_role_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE permission (
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT ck_permission_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE account_role (
  account_id BIGINT UNSIGNED NOT NULL,
  role_id BIGINT UNSIGNED NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (account_id, role_id),
  CONSTRAINT fk_account_role_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_account_role_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_account_role_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE role_permission (
  role_id BIGINT UNSIGNED NOT NULL,
  permission_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (role_id, permission_code),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES permission(code) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_role_permission_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE warehouse (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name VARCHAR(120) NOT NULL,
  priority INT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  fulfillment_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  CONSTRAINT uk_warehouse_code UNIQUE (code),
  CONSTRAINT ck_warehouse_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE account_warehouse (
  account_id BIGINT UNSIGNED NOT NULL,
  warehouse_id BIGINT UNSIGNED NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  updated_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
  PRIMARY KEY (account_id, warehouse_id),
  CONSTRAINT fk_account_warehouse_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_account_warehouse_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouse(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT ck_account_warehouse_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

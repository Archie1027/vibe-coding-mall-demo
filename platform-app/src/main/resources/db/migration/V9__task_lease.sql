-- Candidate only. Do not apply without a separate, exact-source database approval.
-- No scheduler, payment adapter, task row or business seed is enabled by this migration.
CREATE TABLE task_lease (
  task_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  owner_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  lease_until DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

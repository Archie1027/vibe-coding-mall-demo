-- Preserve all existing accounts and password hashes; allow Unicode usernames.
-- Apply only after the user approves this specific migration.
ALTER TABLE account
  MODIFY COLUMN username VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

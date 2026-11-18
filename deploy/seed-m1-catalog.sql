-- Optional sample data for an empty local demo database.
-- 4 categories + 4 brands + 6 assets + 6 products + 6 SKUs + 6 links = 32 rows.
-- Run in one transaction after V2. Do not rerun against populated tables.
START TRANSACTION;
INSERT INTO category (id, name, parent_id, sort_order, enabled) VALUES
  (101, '显卡', NULL, 1, TRUE), (102, '处理器', NULL, 2, TRUE),
  (103, '内存', NULL, 3, TRUE), (104, '外设', NULL, 4, TRUE);
INSERT INTO brand (id, name, enabled) VALUES
  (201, 'NVIDIA', TRUE), (202, 'Intel', TRUE), (203, 'Samsung', TRUE), (204, 'ROG', TRUE);
-- size_bytes are the lengths of the checked-in web-shared/public/assets/products files.
INSERT INTO asset (id, storage_key, content_type, size_bytes, visibility, owner_id) VALUES
  (301, 'rtx-4080-super.jpg', 'image/jpeg', 7958, 'PUBLIC', NULL),
  (302, 'intel-i7-14700k.jpg', 'image/jpeg', 7958, 'PUBLIC', NULL),
  (303, 'samsung-990-pro-2tb.jpg', 'image/jpeg', 7958, 'PUBLIC', NULL),
  (304, 'rog-strix-scope.webp', 'image/webp', 1692, 'PUBLIC', NULL),
  (305, 'rtx-4070-super.jpg', 'image/jpeg', 7958, 'PUBLIC', NULL),
  (306, 'samsung-980-1tb.png', 'image/png', 2682, 'PUBLIC', NULL);
INSERT INTO product (id, name, category_id, brand_id, description, featured, state, rating_sum, rating_count) VALUES
  (2001, 'GeForce RTX 4080 SUPER 16GB 显卡', 101, 201, 'NVIDIA GeForce RTX 4080 SUPER 显卡。', TRUE, 'ON_SALE', 0, 0),
  (2002, 'Core i7-14700K 高性能处理器', 102, 202, 'Intel Core i7-14700K 处理器。', TRUE, 'ON_SALE', 0, 0),
  (2003, '990 PRO 2TB PCIe 4.0 固态硬盘', 103, 203, 'Samsung 990 PRO 2TB 固态硬盘。', TRUE, 'ON_SALE', 0, 0),
  (2004, 'ROG 电竞机械键盘 RGB 版', 104, 204, 'ROG 电竞机械键盘 RGB 版。演示目录条目。', TRUE, 'ON_SALE', 0, 0),
  (2005, 'GeForce RTX 4070 SUPER', 101, 201, 'NVIDIA GeForce RTX 4070 SUPER 显卡。', FALSE, 'ON_SALE', 0, 0),
  (2006, '980 1TB NVMe 固态硬盘', 103, 203, 'Samsung 980 1TB 固态硬盘。', FALSE, 'ON_SALE', 0, 0);
INSERT INTO sku (id, product_id, code, specs_json, specs_hash, price_minor, enabled) VALUES
  (3001, 2001, 'RTX4080SUPER-16G', '[{"name":"显存","value":"16GB"}]', SHA2('[{"name":"显存","value":"16GB"}]', 256), 899900, TRUE),
  (3002, 2002, 'I7-14700K', '[{"name":"型号","value":"i7-14700K"}]', SHA2('[{"name":"型号","value":"i7-14700K"}]', 256), 289900, TRUE),
  (3003, 2003, 'SAMSUNG-990PRO-2TB', '[{"name":"容量","value":"2TB"}]', SHA2('[{"name":"容量","value":"2TB"}]', 256), 119900, TRUE),
  (3004, 2004, 'KB-ROG-RGB', '[{"name":"版本","value":"RGB版"}]', SHA2('[{"name":"版本","value":"RGB版"}]', 256), 89900, TRUE),
  (3005, 2005, 'RTX4070SUPER', '[{"name":"型号","value":"RTX 4070 SUPER"}]', SHA2('[{"name":"型号","value":"RTX 4070 SUPER"}]', 256), 529900, TRUE),
  (3006, 2006, 'SAMSUNG-980-1TB', '[{"name":"容量","value":"1TB"}]', SHA2('[{"name":"容量","value":"1TB"}]', 256), 69900, TRUE);
INSERT INTO product_asset (product_id, asset_id, sort_order) VALUES
  (2001, 301, 0), (2002, 302, 0), (2003, 303, 0),
  (2004, 304, 0), (2005, 305, 0), (2006, 306, 0);
COMMIT;

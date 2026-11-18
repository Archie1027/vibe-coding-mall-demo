# vibe coding商城demo

智采商城的公开演示源码。项目包含顾客商城、管理工作台和 Java 模块化后端，已实现商品目录、地址、购物车、报价下单、订单取消以及开发环境的模拟付款后端。发货、确认收货与真实支付渠道尚未完成。

## 目录

- **web-storefront、web-admin、web-shared** — Vue 3 商城、管理端和共享组件。
- **platform-\*** — Spring Boot 入口、身份、目录、库存和交易模块。
- **docs** — 产品、需求、接口、开发文档与 OpenAPI 设计稿。设计稿包含后续规划。
- **prototype** — 可直接打开的静态界面原型。
- **deploy** — 配置示例、开发 Redis 配置和 6 件示例商品。公开版图片均为占位素材。

## 本地构建

需要 JDK 21、Node.js 22.12 或更新版本。运行后端还需要 MySQL 8 和 Redis。

```bash
npm ci
npm run build
npm run test:web
./mvnw test
```

Windows 可用 `mvnw.cmd test` 执行后端测试。

1. 在本机创建空的 `zhicai_dev` MySQL 数据库。按 `deploy/.env.example` 为启动进程设置数据库和 Redis 环境变量。把实际密码留在本机。
2. 在空库初始化时设置 `FLYWAY_ENABLED=true`，运行 `./mvnw -pl platform-app -am package -DskipTests`，再以 `java -jar platform-app/target/platform-app.jar` 启动后端。迁移完成后恢复默认关闭 Flyway。
3. 如需试用目录，在该空库中执行一次 `deploy/seed-m1-catalog.sql`。如需本地登录，先在同一空库设置 `M0_ADMIN_PASSWORD`、`M0_PURCHASER_PASSWORD`、`M0_APPROVER_PASSWORD` 和 `M0_CUSTOMER_PASSWORD`，用不同的 12–72 字节 ASCII 密码，运行一次后端并追加 `--spring.profiles.active=dev-bootstrap --app.dev-bootstrap.enabled=true --spring.main.web-application-type=none`。初始化创建四个同名演示账户。之后用普通参数重启后端。
4. 在两个终端分别执行 `npm run dev:storefront` 和 `npm run dev:admin`，按终端输出打开本地端口。Vite 将 `/api` 请求转发到 `127.0.0.1:8080`。

本地写入开关默认关闭。依赖原开发库验收基线的测试只用于查阅，公开版移除了对应基线、摘要和运行脚本。公开版适合查阅源码和在独立演示库运行。

# 商品与库存服务

一个可独立运行的 Java REST 服务，提供商品目录、库存调整流水、普通销售、限额预售、订单创建和库存预留。服务使用 PostgreSQL 持久化，以数据库事务、行锁和幂等键处理并发请求。

## 能力范围

- 商品创建、更新、停用、详情查询、名称或编号搜索、分页列表。
- 库存调整及流水查询；实物库存不能被调低到普通销售已预留数量以下。
- 普通销售按可用实物库存预留；预售按商品配置名额，不把预售名额记作实物库存。
- 多商品订单在同一事务内校验和预留；其中任一商品失败时整单回滚。
- `Idempotency-Key` 防止请求重试重复创建订单；同 key 不同请求返回 `409 Conflict`。
- 取消未完成订单并释放预留；重复取消不会重复释放。
- Swagger UI、健康检查、Flyway 数据库迁移、Docker Compose 和 GitHub Actions。

当前不包含支付、物流、退款、用户认证或前端商城。订单状态仅表示 `RESERVED` 或 `CANCELLED`；预留订单不代表已支付或已完成销售。项目面向本机演示和学习，不应直接暴露到公网使用。

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Java | 21 LTS |
| Spring Boot | 4.1.1 |
| PostgreSQL | 18.6 |
| Maven Wrapper | Maven 3.9.16 / Wrapper 3.3.4 |
| Springdoc OpenAPI | 3.1.1 |

Spring Boot 管理 Spring JDBC、Flyway、PostgreSQL JDBC 驱动和测试依赖的兼容版本。应用以 `SELECT ... FOR UPDATE` 按商品编号顺序锁定库存行，并在同一事务中写订单、预留计数和库存流水。订单幂等键、请求摘要和订单结果保存在 PostgreSQL。

## 快速启动

需要 Docker Desktop 或 Docker Engine 的 Compose 插件。数据库密码由当前 shell 环境提供，仓库不包含 `.env` 或默认密码。

PowerShell 7：

```powershell
$env:DB_PASSWORD = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
docker compose up --build -d
```

Linux / macOS：

```sh
export DB_PASSWORD="$(openssl rand -hex 32)"
docker compose up --build -d
```

等待应用就绪后打开：

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康状态：<http://localhost:8080/actuator/health>

Compose 将数据库端口和服务端口绑定到本机回环地址。数据库使用名为 `postgres-data` 的 Docker volume；再次启动同一数据库时，需在当前 shell 中继续使用相同的 `DB_PASSWORD`。更换密码前应先更新数据库凭据。

## 本机 Maven 构建与测试

只需安装 JDK 21；Maven Wrapper 会下载并校验固定版本的 Maven。Testcontainers 集成测试需要 Docker，Docker 不可用时该测试类会跳过；无 Docker 环境仍会运行普通单元测试。

```sh
./mvnw -B -ntp verify
```

Windows PowerShell：

```powershell
.\mvnw.cmd -B -ntp verify
```

如果已有外部 PostgreSQL，也可以直接运行应用：

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/inventory'
$env:SPRING_DATASOURCE_USERNAME = 'inventory'
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
.\mvnw.cmd spring-boot:run
```

数据库 schema 由启动时的 Flyway 迁移创建。`V2` 加入三条演示商品数据；数据存于 PostgreSQL，不会在应用重启时重置。

## API 示例

搜索商品：

```sh
curl 'http://localhost:8080/api/products?q=键盘&page=0&size=20'
```

调整实物库存并记录原因：

```sh
curl -X POST 'http://localhost:8080/api/inventory/SKU-1002/adjustments' \
  -H 'Content-Type: application/json' \
  -d '{"quantityDelta":5,"reason":"盘点入库"}'
```

创建包含普通销售商品和预售商品的订单：

```sh
curl -X POST 'http://localhost:8080/api/orders' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-order-001' \
  -d '{"items":[{"productId":"SKU-1001","quantity":1},{"productId":"SKU-1003","quantity":2}]}'
```

再次发送相同 key 和请求会返回同一订单，不会重复预留。取消订单：

```sh
curl -X POST 'http://localhost:8080/api/orders/<订单编号>/cancel'
```

## API 概览

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/products` | 创建商品及初始实物库存 |
| `GET` | `/api/products` | 搜索、筛选和分页 |
| `GET` | `/api/products/{productId}` | 商品及库存摘要 |
| `PUT` | `/api/products/{productId}` | 更新商品和销售规则 |
| `DELETE` | `/api/products/{productId}` | 停用商品，保留历史订单 |
| `GET` | `/api/inventory/{productId}` | 查询库存和预留数 |
| `POST` | `/api/inventory/{productId}/adjustments` | 调整实物库存 |
| `GET` | `/api/inventory/{productId}/movements` | 分页查询库存流水 |
| `POST` | `/api/orders` | 创建多商品订单并预留库存 |
| `GET` | `/api/orders/{orderId}` | 查询订单快照和状态 |
| `POST` | `/api/orders/{orderId}/cancel` | 取消订单并释放预留 |

订单创建需要 `Idempotency-Key` 请求头。列表页从 0 开始，每页最多 100 条。普通库存不足、预售名额不足或幂等 key 与请求内容不匹配时返回 `409`。

## 工程说明

- [目标报告](docs/target-report.md)：扩展前的目标和验收标准。
- [实现报告](docs/implementation-report.md)：实际实现与验证记录。
- [事务、幂等与并发设计](docs/concurrency-and-idempotency.md)：数据库锁、事务边界和测试场景。
- [数据库迁移](src/main/resources/db/migration)：schema 和演示数据。
- [GitHub Actions](.github/workflows)：构建、测试和 CodeQL。
- 本机 `screenshots/` 中的原实验终端截图保留在工作区，没有纳入服务仓库发布；它们不代表当前 REST API 的界面。

## 安全与发布说明

- 数据库密码由环境变量传入；`.env`、本地数据目录、上传目录和构建输出均被 Git 忽略。
- Actuator 只暴露健康和基本信息端点；API 没有认证、权限控制或请求限流，不要直接公开部署。
- GitHub Actions 执行测试和 CodeQL；Dependabot 配置检查 Maven、Docker 镜像和 Actions 更新。
- 仓库当前没有附加开源许可证。公开可见不等于授予复制或再分发许可；若要开源，需由权利人选择许可证。

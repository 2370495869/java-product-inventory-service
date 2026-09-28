# 项目目标报告（实施前）

> 本文件记录本次扩展的目标、边界和验收方式，不代表这些能力已经实现。完成情况以 `docs/implementation-report.md` 的实施后记录为准。

## 项目现状

当前项目是一个 Java 17、无外部依赖的命令行实验：商品和库存只保存在进程内存中，入口演示商品查询、同步扣减、普通库存/预售策略和单例日志。项目已有 README、Maven 配置、启动脚本、GitHub Actions 工作流和实验截图。

开始扩展时，当前 `main` 分支没有提交历史，且没有配置 Git remote；现有项目文件均为未跟踪文件。工作区只有 JDK 21 可用，未发现系统 Maven、Docker CLI 或 GitHub CLI。因此，所有现有文件都按用户已有内容保留；后续需使用 Maven Wrapper，Docker 和 GitHub 发布验证能否执行取决于运行环境是否可用。

## 目标范围

将项目扩展为可独立启动的商品、库存与订单 REST 服务，支持：

- 商品创建、更新、停用、详情查询、名称搜索和分页列表。
- 库存增减调整及不可变库存流水；库存调整不得将实物库存降到已预留数量以下。
- 普通销售只在可售实物库存足够时预留；预售按商品设置预售名额，以事务方式占用名额，不把预售名额当成实物库存。
- 一个订单可包含多种商品；创建订单时校验并预留全部商品，任一商品失败则整体回滚。
- 通过 `Idempotency-Key` 防止重复创建订单；相同 key、相同请求返回既有订单，相同 key、不同请求返回冲突。
- 可取消尚未完成的预留订单并释放对应普通库存或预售名额，取消操作可重复调用而不会重复释放。
- 提供健康检查、OpenAPI/Swagger 交互文档、Docker Compose 启动方式和中文使用说明。

本次不实现支付、物流、退款、用户认证、前端商城或多服务拆分。订单只表示库存预留；未接入支付系统前，不把预留订单描述为已支付或已完成销售。预售容量以“当前有效预留数量”为准，取消订单会释放容量。

## 技术方案

- Java 21 LTS，以兼容当前工作区 JDK 并保留长期维护窗口。
- Spring Boot 4.1.1，采用 Spring MVC、Spring JDBC 和声明式事务；以 SQL 行锁在 PostgreSQL 中串行化同一商品的库存操作。
- PostgreSQL 18.6 保存商品、库存计数、订单、幂等请求和库存流水；Flyway 维护版本化数据库迁移。
- Springdoc OpenAPI 3.1.1 提供 API 文档与 Swagger UI。
- Maven Wrapper 固定 Maven 3.9.16，使使用者无需预装 Maven。
- Docker Compose 启动 PostgreSQL 和服务容器；数据库密码只从本机环境变量读取，不把 `.env` 或密码提交到仓库。
- JUnit 自动化测试覆盖服务/HTTP 校验、数据库事务回滚、幂等重试、库存并发预留和取消释放；GitHub Actions 执行构建与测试。

选择依据截至 2026-09-28：Spring Boot 4.1.1 要求至少 Java 17，并支持到 Java 26；Temurin 将 Java 21 标为 LTS；PostgreSQL 18 当前受支持到 2030-11-14；Springdoc 3.x 与 Spring Boot 4 兼容。[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Temurin 支持路线](https://adoptium.net/support/)、[PostgreSQL 版本支持](https://www.postgresql.org/support/versioning/)、[Springdoc 兼容说明](https://springdoc.org/faq.html)。

## 验收标准

1. 在仅安装受支持 JDK 的机器上可使用 `./mvnw verify` 或 `mvnw.cmd verify` 完成构建与自动化测试。
2. Flyway 从空 PostgreSQL 数据库完成迁移；服务可通过 Docker Compose 启动并提供健康状态。
3. 商品管理、搜索、库存调整流水、普通销售、限额预售、订单创建和库存预留都可通过 HTTP API 实际调用。
4. 并发创建订单不会把普通可售库存或预售容量预留到上限以外；包含多商品的订单失败时不留下部分预留或流水。
5. 重放同一幂等请求不重复创建订单/预留，复用 key 提交不同请求会收到明确冲突。
6. 本地自动化测试、启动检查和安全检查均有可复现命令；实现报告只记录实际执行及结果。
7. 推送前审核最终 diff 和暂存文件，排除密码、令牌、真实个人信息、`.env`、本机数据库、上传目录、构建产物和无法确认可公开的截图内容。
8. 只在确认目标 GitHub 仓库属于本项目且公开可见后，提交当前项目并推送 `main`，不强推，不改动其它三个项目。

## 实施状态

截至本报告编写时，以上功能均为目标，尚未在本项目中实现或验证。GitHub 身份连接可查询，但本地仓库尚无 remote；仓库创建与推送会在本地实现、审核和验证完成后再处理。

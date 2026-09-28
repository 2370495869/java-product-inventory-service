# 实现报告：商品与库存服务

> 更新日期：2026-09-28。本文记录本次扩展实际完成的代码和验证结果；扩展前目标见 [目标报告](target-report.md)。

## 实际实现

- 商品 REST API 支持创建、更新、停用、详情、名称/编号搜索、销售方式筛选和分页。
- 库存 API 支持实物库存调整、库存摘要和分页流水；调减不能低于普通订单的有效预留量。
- 普通销售从实物可用量预留；预售按商品 `presaleLimit` 控制独立名额，不增加实物库存。
- 多商品订单在一个数据库事务中完成校验、订单/明细写入、库存预留和流水写入；任何一步失败都会回滚整单。
- 订单通过 `Idempotency-Key` 唯一约束和规范化请求的 SHA-256 摘要实现幂等。相同请求返回既有订单，不同请求复用同一 key 返回 `409`。
- PostgreSQL 行锁按商品编号排序；条件更新和表约束共同防止普通库存超卖、预售超限。取消订单锁定订单和商品库存，释放预留并写入流水；重复取消不重复释放。
- 提供 Flyway schema/演示数据迁移、健康检查、OpenAPI/Swagger UI、Docker Compose、Dockerfile、Maven Wrapper、构建测试工作流、CodeQL 和 Dependabot 配置。
- 中文 README 给出运行方式、配置、API、范围限制和安全说明。

## 技术选择

| 组件 | 版本 | 用途 |
| --- | --- | --- |
| Java | 21 LTS | 运行时与编译目标 |
| Spring Boot | 4.1.1 | HTTP、校验、事务与数据库集成 |
| PostgreSQL | 18.6 | 商品、库存、订单、幂等键和流水持久化 |
| Flyway | Spring Boot 管理的兼容版本 | 版本化数据库迁移 |
| Springdoc OpenAPI | 3.1.1 | OpenAPI 与 Swagger UI |
| Maven Wrapper / Maven | 3.3.4 / 3.9.16 | 固定构建工具版本，无需预装 Maven |
| Testcontainers | 2.0.5 | 使用真实 PostgreSQL 的事务和并发集成测试 |

版本选择参考 [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[Temurin 支持路线](https://adoptium.net/support/)、[PostgreSQL 版本支持](https://www.postgresql.org/support/versioning/) 和 [Springdoc 兼容说明](https://springdoc.org/faq.html)。Compose 使用 PostgreSQL 18 的 `/var/lib/postgresql` 数据卷路径。

## 项目结构

- `src/main/java/com/example/inventory/`：服务入口、API、业务服务、领域类型和 JDBC 持久化。
- `src/main/resources/db/migration/`：schema 和演示数据迁移。
- `src/test/java/com/example/inventory/`：分页单元测试与 PostgreSQL 集成测试，覆盖回滚、同 key 重放/冲突、同 key 并发、并发库存和预售取消。
- `compose.yaml`、`Dockerfile`：本地服务和数据库容器配置。
- `.mvn/wrapper/`、`mvnw`、`mvnw.cmd`：Maven Wrapper。
- `.github/workflows/`、`.github/dependabot.yml`：CI、CodeQL 和依赖更新。
- `docs/concurrency-and-idempotency.md`：事务、锁顺序和幂等设计细节。

扩展前实验源码、根目录 class 文件和终端截图均保留在本机，没有修改或纳入本次发布范围。Maven 明确只编译新的 `com/example/inventory` 服务源码，避免旧演示入口混入可执行 JAR。截图不纳入发布，以免把本机实验画面误认为服务 UI。

## 验证记录

2026-09-28 在 JDK 21 环境执行 `mvnw.cmd -B -ntp verify`，构建成功；新增的两个普通单元测试通过。五个 Testcontainers PostgreSQL 集成测试因本机未安装/运行 Docker 而跳过，不能把它们记作本机通过。GitHub Actions 中的 `verify` 用于在具备 Docker 的 runner 上执行这些集成测试。

为验证服务本身，使用独立的临时 PostgreSQL 18.6 数据库运行可执行 JAR。Flyway 两个迁移完成；健康端点返回 `UP`，数据库中有三条演示商品，`/v3/api-docs` 返回 OpenAPI 3.1.0。HTTP 检查结果：相同 key 重放返回 `200` 且订单 ID 相同；相同 key 携带不同订单内容返回 `409`；12 个并发各请求一件、总实物库存为 6 时，恰好 6 个返回创建成功、6 个因库存不足返回 `409`，最终预留量为 6；预售上限为 2 时第三件请求被拒绝，取消后预售预留量回到 0。多商品订单缺货时返回 `409`，先前已处理商品的预留和流水均回滚；补足库存后复用失败请求的 key 可成功重试；重复取消保持 `CANCELLED` 且没有重复释放流水。Docker Compose 未在本机启动，因为环境没有 Docker CLI/守护进程。

构建和 HTTP 验证之外，本次发布前还检查暂存清单、敏感信息模式、忽略项和构建产物；本机数据库、`.env`、上传目录、`target/` 和原实验截图均不纳入发布。

## 当前边界

本项目不实现支付、物流、退款、登录认证、权限控制或限流。应用 API 没有鉴权，不应直接暴露到公网。订单仅代表库存预留，不表示已付款或已完成销售。公开仓库不附带开源许可证；公开可见本身不会授予再分发许可。

## 扩展前实验版本记录（历史）

以下内容记录本次工作开始前的实验版本，不描述扩展后的服务。

### 原实验实现

原版本是 Java 17 的单文件演示，包含内存商品列表、库存扣减策略、日志单例和容器组装。它没有数据库持久化、REST API、跨请求事务或分布式库存控制。本次保留其原始源码，不将其合并进新的服务实现。

扩展前报告记载的实现细节：旧实验入口使用 `BigDecimal` 表示商品价格，商品记录不可变；日志为静态单例并用 `System.nanoTime` 记录耗时；普通库存和预售演示通过策略接口切换；输入会检查空商品、重复编号、无效价格和非正扣减量；演示库存读写在服务边界同步。扩展前按 Java 17 编译目标、使用 JDK 21 完成过编译，未配置或运行自动化测试。旧 README、跨平台脚本、GitHub Actions 和实验截图属于原实验材料；本次保留旧源码/截图于本地工作区，新的服务则有独立 API、持久化、迁移和事务实现。

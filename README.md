# 数据库备份管理（Spring Boot + MyBatis + React）

后端为 Java 21 / Spring Boot，前端为 React。PostgreSQL 存储备份元数据与登录会话，Docker Compose 提供单台虚拟机部署；Web 后端可横向扩展，调度器保持一个实例。

## 整体架构

```text
React 页面
    ↓ HTTP / JSON
Spring MVC + Spring Security
    ↓
ApiController / AuthController
    ↓
备份同步、规则判断、覆盖率等 Service
    ↓
CatalogRepository（业务持久化门面，不包含 SQL）
    ↓
MyBatis Mapper 接口 + XML SQL
    ↓
PostgreSQL（正式环境）/ H2 PostgreSQL 兼容模式（本地与测试）
```

业务持久化已统一为 MyBatis，不使用 JPA，也没有在业务类中直接使用 `JdbcTemplate`。API 和业务服务主要通过 `CatalogRepository` 访问数据；该类负责数据库自动登记、备份幂等写入、同步记录主键回填等持久化流程，但具体 SQL 全部位于 Mapper XML。登录认证和首个管理员初始化直接使用 `UserMapper`，避免再增加一层只做转发的包装。

### 数据库结构和 SQL 的职责

- **Flyway 管理 DDL 和基础数据**：正式 PostgreSQL 使用 `src/main/resources/db/migration`，本地 H2 使用 `src/main/resources/db/local`。建表、索引、约束、默认规则和后续结构升级只能通过新增版本化迁移脚本完成，应用启动时不会由 ORM 自动生成或修改表。
- **MyBatis 管理业务查询和 DML**：Java Mapper 接口位于 `src/main/java/com/example/backupmanager`，对应 XML 位于 `src/main/resources/mapper`。`application.yml` 通过 `classpath*:mapper/*.xml` 加载映射，并开启下划线字段名到 Java 驼峰属性名的转换。
- **Spring Session 保持 JDBC 存储**：`SPRING_SESSION` 和 `SPRING_SESSION_ATTRIBUTES` 由 Flyway 创建，Spring Session JDBC 负责读写，使多个后端实例可以共享登录状态。这是框架基础设施，不属于业务 ORM。
- **Flyway 自身使用 JDBC 执行迁移**：它只在启动阶段校验和升级结构；运行期的业务读写由 MyBatis 完成。

当前 Mapper 与数据表的对应关系：

| Mapper | 主要数据表 | 职责 |
| --- | --- | --- |
| `DatabaseMapper` | `database_catalog` | 数据库资产登记、列表、详情、元数据和监控起始日 |
| `BackupMapper` | `backup_record` | 备份记录筛选、规则核验查询、日历统计和幂等新增/更新 |
| `RuleMapper` | `backup_rule` | 日备、月备、年备规则读取与配置 |
| `SyncRunMapper` | `sync_run` | 同步任务开始、完成、计数、错误和最近执行记录 |
| `UserMapper` | `app_user` | 登录账号读取、管理员初始化和用户创建 |

关键目录如下：

```text
src/main/java/com/example/backupmanager/
  *Mapper.java                 MyBatis Mapper 方法定义
  CatalogRepository.java      面向业务服务的无 SQL 持久化门面
  *Service.java               同步、规则和覆盖率业务逻辑
src/main/resources/
  mapper/*.xml                PostgreSQL/H2 兼容的业务 SQL 与结果映射
  db/migration/V*.sql         正式 PostgreSQL 的 Flyway 迁移
  db/local/V*.sql             本地 H2 的 Flyway 迁移
src/test/java/com/example/backupmanager/
  MyBatisPersistenceTests.java MyBatis 与迁移脚本集成测试
```

## 在 VS Code 中运行

前置环境为 Java 21、Node.js 20.19+ 和 pnpm；请通过 `JAVA_HOME` 或 `PATH` 提供 Java，通过 `PATH` 提供 pnpm。仓库自带 Maven Wrapper，不要求单独安装 Maven。项目可以克隆到任意目录。Windows 版 VS Code 可直接使用仓库中的任务；其他系统可按照“本地开发”一节运行对应命令。

在 VS Code 中运行任务（菜单“终端”→“运行任务”）：先运行“1. 构建后端”，再分别运行“2. 启动后端（本地数据库）”和“3. 启动前端”。打开 `http://127.0.0.1:5173/`。首次管理员用户名为 `admin`，随机生成的密码保存在项目根目录的 `.local-credentials` 文件中。该文件和本地数据库 `data/` 已加入忽略列表，请勿提交到仓库。

通过 VS Code 本地启动脚本首次运行时，会生成 72 个以 `DEMO_` 开头的数据库和 3,556 条备份记录，用于查看列表、日历和规则检查效果。`DEMO_pay_001` 展示连续成功日备，`DEMO_trade_017` 展示失败备份，8 个 `DEMO_empty_...` 数据库完全没有备份；其他库混合成功、取消、进行中和缺失。演示库的月备、年备可以在详情矩阵中查看；真实月备和年备在配置 OceanProtect 后从副本接口同步。样例只会在本地配置且 `APP_DEMO_SEED=true` 时生成，重复执行会按固定编号更新，不会成倍增加。手动启动时可自行设置该环境变量。

本地配置使用项目目录下的 H2 文件数据库，便于在没有 Docker/PostgreSQL 的电脑上运行。正式部署仍使用下面的 PostgreSQL 与 Docker Compose 配置。真实平台凭据未写入本地项目；日备使用 `BACKUP_DAILY_*`，月备/年备使用 `OCEANPROTECT_*` 环境变量。本地调度默认关闭。

## 已实现

- 多人登录；管理员可创建查看者和管理员账号。
- 数据库资产列表采用白底蓝色的紧凑表格，包含 DBID、标签、有效标识、等级、框架、版本、子系统、开发人员、DBA 和服务单元，支持关键词搜索、框架/监控状态筛选和分页。
- 点击数据库名进入详情：显示建库日期和资产资料，管理员可以编辑资料；真实库尚未提供的字段显示“—”。
- 详情按实际规则逐格展示日备、月备和年备。默认日备 7 格、月备 12 格，年备按建库/监控以来已到期的年末计划生成。新建库不会要求建库前的备份。调整保留期限后，格子数量和规则说明随之变化。
- 黄色代表确认成功，红色代表缺失或失败，蓝色代表进行中，灰色代表仍在宽限期或未核验。点击格子可查看说明和对应记录，下面保留全部历史备份的筛选查询。
- 按数据库查看全部历史备份、按日期查看备份日历、按状态和类型筛选。
- 日备规则：每天一份，保留期配置为 7 天，计划日后 2 天仍无成功备份则显示缺失。
- 月备和年备规则分别为每月 1 日、每年 12 月 31 日，保留一年和永久；OceanProtect 接口已经接入。规则默认关闭，管理员可以先按现有或演示数据启用检查；正式使用建议首次真实同步并确认资源名称和策略日期后再启用。
- 每天按北京时间 06:00 分别同步日备平台和已配置的 OceanProtect 月备/年备；管理员也可在“同步记录”中分别手动执行。
- 备份触发接口尚未接入，界面没有虚假的触发操作。

`successed` 视为成功、`DISPATCHING` 为进行中、`cancel` 为失败。OceanProtect 的 `available` 会在入库时转换为 `successed`，文档没有定义的其他状态保留原值，不会被误判为成功。一个成功记录只覆盖一个计划日；无备份记录或逾期仍进行中的备份会在宽限期结束后显示缺失。日备优先按明确的备份日期归属当天，不会用次日日备掩盖前一天的缺口；月备/年备可匹配宽限期内的非计划日备份。规则未启用时显示未核验，演示库继续使用模拟记录展示效果。

**准确性边界：**已知接口的 `time` 是记录更新时间。只有接口提供独立的 `backupDate`、`backup_date` 或 `date` 时，记录才可用于确认计划日达标。否则页面显示“推定”“待核对日期”，不会误判成功。接口若没有不可变 `id`、`backupId` 或 `taskId`，状态更新可能形成多条记录。保留 7 天/一年目前是规则配置，实际备份文件是否仍存在需要“当前备份清单”接口才能核验。

## Docker 启动

1. 将 `.env.example` 复制为 `.env`。设置强 `DB_PASSWORD`、`BOOTSTRAP_ADMIN_PASSWORD`、日备平台配置，以及 OceanProtect 地址、用户名和密码。不要把 `.env` 提交到代码仓库。
2. 执行 `docker compose up -d --build`。浏览器打开 `http://服务器:8080`，用 `.env` 中的管理员账号登录。
3. 首次管理员建立后，可从 `.env` 删除 `BOOTSTRAP_ADMIN_PASSWORD` 并重启服务；其他用户在“用户”页创建。
4. 管理员可在“同步记录”中先执行一次手动同步。后台调度器每天北京时间 06:00 自动同步，`BACKUP_SYNC_CRON` 可调整。

在单台虚拟机上运行一个 `backend`、一个 `scheduler` 即可。多实例 Web 服务可执行 `docker compose up -d --scale backend=2`；登录会话保存在 PostgreSQL，`scheduler` 仍只运行一个实例。实际对外使用时，应通过 HTTPS 反向代理发布前端，并设置 `COOKIE_SECURE=true`。

## 本地开发

- 后端：Java 21。直接运行 `./mvnw spring-boot:run` 时默认连接 PostgreSQL，需要配置 `DB_URL`、`DB_USER`、`DB_PASSWORD` 和首次管理员环境变量；Windows 本地演示可使用 VS Code 任务或 `scripts/run-local-backend.ps1`，它会启用 `local` 配置并连接项目 `data/` 下的 H2 文件数据库。
- 前端：Node 20.19+，进入 `frontend`，运行 `pnpm install` 和 `pnpm dev`。开发服务器会把 `/api` 转发给 `localhost:8080`。
- 后端测试：运行 `./mvnw test`。`MyBatisPersistenceTests` 使用内存 H2 的 PostgreSQL 兼容模式；OceanProtect 测试使用本机模拟 HTTP 服务验证认证、SLA/副本分页、令牌刷新、字段解析和月年备入库；其他测试验证规则判断和日备平台解析。
- 后端完整构建：Windows 运行 `scripts/build-backend.ps1`，其他系统运行 `./mvnw package`；Maven 会在打包前执行测试。前端构建：进入 `frontend` 后运行 `pnpm build`。

增加或修改持久化功能时，先修改对应的 `*Mapper.java` 方法签名和 `mapper/*Mapper.xml` SQL；如果表结构发生变化，再分别为正式库和本地库新增同版本号的 Flyway 迁移。不要直接改已经执行过的历史迁移脚本，否则已部署数据库会出现校验不一致。

## 日备接口

向 `BACKUP_DAILY_URL` POST `{"pageIndex":1,"pageSize":20000,"customParams":{}}`，Bearer 令牌从环境变量读取。支持顶层 JSON 数组及常见 `data.records`、`data.rows`、`data.list` 等结构；如果返回列表在其他位置，用 `BACKUP_DAILY_DATA_PATH` 指定，如 `data.items`。系统逐页读取到空页，并检查重复分页。

## OceanProtect 月备/年备接口

系统按 OceanProtect 文档执行以下调用链：

1. `POST /v1/auth/token` 获取 `X-Auth-Token`。
2. 从 0 页开始分页调用 `GET /v1/slas`，解析 `policy_list[].schedule.trigger_action`，找出 `month` 和 `year` 策略。
3. 对每个匹配的 SLA 名称调用 `GET /v1/copies`，使用 URL 编码后的 `conditions=%sla_name%:<名称>`，每页默认 100 条且严格小于 200。
4. 从副本读取 `resource_name`、`uuid`、`display_timestamp`、`status`、`generated_by` 和字符串形式的 `sla_properties`。只接受 `generated_by=sla/Backup` 且未标记归档或复制的备份副本；明确属于复制、归档等非备份策略的数据会被跳过。
5. 只有副本属性能唯一对应一个月备或年备调度时才按 `monthly`、`yearly` 幂等写入。同一 SLA 中存在多个备份调度且副本没有指出来源策略时，会记入同步结果的“策略类型不明确”数量，不会靠 SLA 名称猜测并误报规则达标。

需要配置：

```text
OCEANPROTECT_BASE_URL=https://oceanprotect-host:25081
OCEANPROTECT_USERNAME=...
OCEANPROTECT_PASSWORD=...
OCEANPROTECT_AUTH_TYPE=STORAGE_SYSTEM
OCEANPROTECT_USER_TYPE=common
OCEANPROTECT_LANGUAGE=1
OCEANPROTECT_PAGE_SIZE=100
OCEANPROTECT_SLA_PAGE_SIZE=100
OCEANPROTECT_MAX_PAGES=1000
```

平台使用内部 HTTPS 证书时，应把 CA 或服务器证书导入运行后端的 JVM truststore，不要关闭 TLS 校验。密码和 Token 不写日志、不写数据库、不返回给前端。

**真实联调仍需确认：**实际 Endpoint、服务账号、证书、脱敏的 SLA/Copy 响应；`resource_name` 是否就是项目中的逻辑数据库名且在所有平台实例中唯一；OceanProtect 完整状态枚举和 `generated_by` 实际取值；同一 SLA 含多个备份调度时是否有可关联到具体策略的副本字段；平台的 `days_of_month` 是否为 1、`days_of_year` 是否为 12 月 31 日。确认首次同步结果后，再在规则页启用月备和年备检查。

**其他待补资料：**日备接口的脱敏 JSON、独立备份日期和任务 ID、库列表接口，以及触发备份接口。当前管理员可手动登记无备份数据库。日备 HTTP 接口只应在可信内网或加密通道中传输令牌。

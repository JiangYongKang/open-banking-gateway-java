# 开放银行第三方接入网关（本地可运行）

一个零外部依赖的开放银行网关演示：合作方持令牌在客户同意书（Consent）授权范围内查询账户、
发起支付指令；在**异常与并发**场景下保证状态迁移合法、支付幂等、限流可控、复式账本守恒且可解释。

本轮新增（面向合作方自助对账与客服后台）：

- **支付结果回查**：成功与业务拒绝都按 `Idempotency-Key` 留痕可查，拒绝原因原样返回，不重新执行；
- **同意书权限分层**：`CONSENTS_READ`（只读）与 `CONSENTS_MANAGE`（可变更）分档授权；
- **版本与一致性**：同意书每次成功迁移推进单调版本号，读取/判定/变更看到同一份最新状态。

- Spring Boot 3.4 / Java 21，仅内存存储，不依赖数据库、外部授权服务器或网络资源。
- 令牌、账户、同意书全部在进程内生成，启动即可联调。

## 1. 本地运行

```bash
mvn spring-boot:run
```

启动后：
- 应用端口 `8080`；
- 自动注入演示账户 `acct-1001`(100000 CNY)、`acct-1002`(0)、`acct-2001`(0)；
- 自动签发两个合作方令牌（启动日志与 `GET /local/tokens` 均可查看）：

| 合作方 | 令牌范围 |
| --- | --- |
| `partner-alpha` | `ACCOUNTS_READ` + `PAYMENTS_INITIATE` + `CONSENTS_READ` + `CONSENTS_MANAGE` |
| `partner-beta` | `ACCOUNTS_READ` + `CONSENTS_READ`（同意书只读，变更一律被拒） |

`/local/**` 仅为本地便利端点（无需认证），不属于 `/api/**` 受保护面。

## 2. HTTP 接口

所有业务接口在 `/api/**` 下，需请求头 `Authorization: Bearer <token>`。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/consents` | 创建同意书：body `{accountIds, scopes}`（需可管理权限） |
| `POST /api/consents/{id}/authorize` | 授权（CREATED → AUTHORIZED），可选 `?expectedVersion=` |
| `POST /api/consents/{id}/suspend` | 暂停（AUTHORIZED → SUSPENDED），可选 `?expectedVersion=` |
| `POST /api/consents/{id}/resume` | 恢复（SUSPENDED → AUTHORIZED），可选 `?expectedVersion=` |
| `POST /api/consents/{id}/revoke` | 撤销（任意非终态 → REVOKED），可选 `?expectedVersion=` |
| `GET  /api/consents` | 本合作方同意书列表（只读权限即可） |
| `GET  /api/consents/{id}` | 同意书详情：状态、`usable`、`version`、`lastModifiedAt` |
| `GET  /api/accounts/{accountId}?consentId=` | 授权范围内查询账户余额 |
| `POST /api/payments` | 提交支付，需请求头 `Idempotency-Key` |
| `GET  /api/payments/by-idempotency/{key}` | 按幂等键回查提交结果（成功或业务拒绝） |

支付报文：

```json
{
  "consentId": "...",
  "fromAccountId": "acct-1001",
  "toAccountId": "acct-1002",
  "amount": 250.00,
  "currency": "CNY"
}
```

### curl 示例

```bash
TOKEN=$(curl -s localhost:8080/local/tokens | jq -r '.[]|select(.partnerId=="partner-alpha")|.token')

CID=$(curl -s -X POST localhost:8080/api/consents -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"accountIds":["acct-1001","acct-1002"],"scopes":["ACCOUNTS_READ","PAYMENTS_INITIATE"]}' \
  | jq -r .consentId)

curl -s -X POST localhost:8080/api/consents/$CID/authorize -H "Authorization: Bearer $TOKEN"
curl -s "localhost:8080/api/accounts/acct-1001?consentId=$CID" -H "Authorization: Bearer $TOKEN"

curl -s -X POST localhost:8080/api/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: pay-001' -H 'Content-Type: application/json' \
  -d '{"consentId":"'$CID'","fromAccountId":"acct-1001","toAccountId":"acct-1002","amount":250,"currency":"CNY"}'

# 按幂等键回查提交结果（成功与业务拒绝都可查）
curl -s localhost:8080/api/payments/by-idempotency/pay-001 -H "Authorization: Bearer $TOKEN"

# 同意书列表/详情（含 version / lastModifiedAt / usable）
curl -s localhost:8080/api/consents -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/consents/$CID -H "Authorization: Bearer $TOKEN"

# 乐观并发：携带期望版本变更，版本不符 -> 409 consent_version_conflict
curl -s -X POST "localhost:8080/api/consents/$CID/suspend?expectedVersion=1" -H "Authorization: Bearer $TOKEN"
```

## 3. 同意书状态机

```
                 authorize
   CREATED  ─────────────────►  AUTHORIZED
      │                            │  ▲
      │ revoke                     │  │ resume
      ▼                       suspend │
   REVOKED  ◄──────────────►  SUSPENDED
      ▲                            │
      └────────── revoke ──────────┘
```

- 合法迁移：`CREATED→AUTHORIZED`、`AUTHORIZED→SUSPENDED`、`SUSPENDED→AUTHORIZED`、
  `CREATED/AUTHORIZED/SUSPENDED → REVOKED`。
- `REVOKED` 为终态；对终态做任何迁移返回 `409 consent_revoked`。
- 过期不主动改字段，而是在每次使用时按 `expiresAt` 即时判定为 `EXPIRED`，
  过期同意书的迁移/支付/查询返回 `409 consent_expired`。
- 非法迁移（如 CREATED 直接 suspend）返回 `409 invalid_consent_state`。

## 4. 身份与授权范围规则

采用两级范围校验：

1. **令牌级**：合作方令牌携带 `scopes`。
   - 无/伪造令牌 → `401 unauthenticated`；过期令牌 → `401 token_expired`。
   - 查询需 `ACCOUNTS_READ`，支付需 `PAYMENTS_INITIATE`；不足 → `403 insufficient_scope`。
2. **同意书级**：业务操作还需同意书处于 `AUTHORIZED`、在有效期内、包含所需 scope
   且账户在授权清单内，否则分别返回 `consent_expired` / `consent_revoked` /
   `invalid_consent_state` / `consent_scope_mismatch` / `account_not_in_consent`。
   创建同意书时申请的 scope 必须是令牌 scope 的子集。

**跨合作方访问不泄漏归属**：访问不存在的同意书与访问他人的同意书，对外都是
`404 {"code":"resource_not_found","message":"consent not found: <id>"}`，
状态码、错误码、消息结构完全一致；仅内部日志以 `reason=not_found|cross_partner` 区分。
该语义对只读合作方同样成立：跨方读取他人同意书也是同一个 404，
不因对方资源是否存在、是否已撤销而给出不同响应。

### 同意书"能看 / 能改"分层

| 令牌携带 scope | 列表/详情 | 创建/授权/暂停/恢复/撤销 |
| --- | --- | --- |
| `CONSENTS_MANAGE` | ✅ | ✅ |
| 仅 `CONSENTS_READ` | ✅ | ❌ 一律 `403 insufficient_scope` |
| 两者都不含（旧令牌） | ✅ | ✅（兼容分层前行为） |

- 只读合作方的任何状态变更请求在**归属判断之前**就被拒绝，与资源是否存在无关；
- 上一轮已在管理同意书的合作方（旧令牌或含 `CONSENTS_MANAGE` 的新令牌）不受分层影响；
- 同意书响应新增 `version`、`lastModifiedAt`、`usable` 三个字段，均为纯追加，
  旧调用方按原字段解析不受影响。

## 5. 幂等与限流

### 幂等与结果回查

- 幂等维度为 `(partnerId, Idempotency-Key)`，不同合作方互不影响。
- 服务端对报文（consentId/from/to/amount/currency）做 SHA-256 指纹：
  - **同键同报文**：直接回放首次判定结果——成功则返回同一 paymentId，只生效一次；
    业务拒绝则原样返回当时的错误码与消息，**不重新执行**；
  - **同键异报文**：返回 `409 idempotency_conflict`，不产生任何账务效果。
    首次提交即使已被业务拒绝，同键换一份报文依然冲突——不能靠改报文把失败绕过去；
  - **业务拒绝（余额不足/同意书不可用/触发限流等）与成功一样落回查记录**，
    判定即终局；仅报文级校验错误（`400 validation_error`）不留痕。
- 并发同键请求在"键级锁"内串行：一个线程执行，其余等待后回放同一结果，保证绝不重复生效。

#### 按幂等键回查

`GET /api/payments/by-idempotency/{key}`（需 `PAYMENTS_INITIATE`）返回首次判定结果：

```json
// 成功
{"idempotencyKey":"pay-001","status":"COMPLETED","payment":{"paymentId":"...","status":"COMPLETED",...},"rejection":null,"recordedAt":"..."}
// 业务拒绝（code 与当时响应一致：insufficient_funds / consent_revoked / rate_limited / ...）
{"idempotencyKey":"pay-002","status":"REJECTED","payment":null,"rejection":{"code":"insufficient_funds","message":"account balance is insufficient"},"recordedAt":"..."}
```

键不存在（或记录已超保留窗口）→ `404 resource_not_found`。

#### 回查记录保留窗口

- 配置项 `obg.idempotency.retention`（默认 `PT24H`）：记录只在该窗口内保证可查；
- 超窗记录被惰性清理（访问时剔除 + 写入时全量清扫），存储不无限增长；
- 清理只影响回查能力：**已生效的支付、账本分录与账户余额不受任何影响**。

### 限流

合作方维度滑动窗口，仅对支付提交计数；**被限流的请求不计数、不记账、不改变任何余额**：

- 第 N+1 次超限请求 → `429 rate_limited`。
- 已存在幂等结果的回放请求直接返回，不受限流影响。

配置项（`application.properties`，均可覆盖）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `obg.consent.default-ttl` | `PT1H` | 同意书默认有效期 |
| `obg.rate-limit.requests-per-window` | `10` | 每合作方每窗口最多支付次数 |
| `obg.rate-limit.window` | `PT1M` | 限流窗口长度 |
| `obg.currency` | `CNY` | 唯一允许币种（不符 → `400 validation_error`） |
| `obg.idempotency.retention` | `PT24H` | 幂等/回查记录保留窗口，超窗可清理 |

其余可区分错误：金额非法/缺幂等键/币种错 → `400 validation_error`；
余额不足 → `422 insufficient_funds`（不对账户做任何改动）。

## 6. 并发正确性、版本语义与账本解释性

### 同意书版本与一致性

- 每个同意书携带 `version`（从 0 起）与 `lastModifiedAt`；**每次成功的状态迁移在同意书锁内
  原子推进二者**，`version` 只增不减，被拒绝的迁移不推进版本。
- 账户查询、同意书详情/列表、支付判定读取的是同一组 volatile 状态，
  总能看到最近一次成功迁移之后的状态——不会出现"已撤销却还能支付"或读判不一致。
- **查询与撤销/暂停并发**：结局二选一且可解释——要么读取/判定在迁移前完整生效，
  要么按新状态拒绝；支付判定在同意书锁内二次校验，读到的状态与判定结果不会互相矛盾。
- **两个变更并发**：迁移在锁内串行并重检状态机，后到者得到可区分拒绝
  （`invalid_consent_state` / `consent_revoked`），不会互相覆盖。
- **乐观并发**：变更端点支持可选 `?expectedVersion=`，与当前版本不一致 →
  `409 consent_version_conflict`，状态与版本均不变；调用方可据此显式防止覆盖他人变更。

### 支付与账本

- **支付与撤销互斥**：支付在同意书对象锁内做二次状态判定，撤销与支付在同一把锁上竞争，
  结果只能是"支付完整生效"或"拒绝支付"，不会半生效。
- **账户锁按账户 id 排序获取**，双向并发转账不会死锁。
- **复式记账**：每笔支付写两条分录（付款方 CREDIT / 收款方 DEBIT），
  有借必有贷、借贷必相等；全部分录净额恒为 0，账户总余额恒等于初始总额。
- 扣款→入账→分录分阶段提交，任一步抛错按阶段反向补偿。

## 7. 本地验证

```bash
# 全部单元/并发/集成测试（33 个用例）
mvn test
```

测试覆盖：

- `ConsentLifecycleTest`：合法/非法状态迁移、过期、撤销、scope 不符、账户越权、跨方 404 同构；
- `PaymentIdempotencyTest`：同键回放、同键异报文冲突、限流、校验失败与余额不足不动账；
- `PaymentLookupTest`（新增）：成功/余额不足/同意书状态/限流四类结果按键回查；
  失败后同键异报文冲突、同键同报文回放拒绝不重新执行；超窗清理不动支付与账本；
- `ConsentReadOnlyIntegrationTest`（新增，MockMvc）：只读合作方列表/详情放行、
  四类变更一律 403、跨方读取 404 同构、旧令牌兼容可管理；
- `ConsentVersionConcurrencyTest`（新增）：版本单调递增、失败迁移不推进版本、
  `expectedVersion` 冲突、并发变更不互相覆盖、查询与撤销并发下读判一致；
- `PaymentConcurrencyTest`：32 线程同键并发只生效一次；60 笔双向并发转账总额守恒；
- `RevokeVsPaymentConcurrencyTest`：40 轮 × 8 线程支付与撤销竞速，两种结局都出现，无半生效；
- `SecurityAccessIntegrationTest`（MockMvc 端到端）：无/伪造/过期令牌、范围不足、跨合作方 404 同构。

测试日志同时输出到控制台，包含：
- 幂等键与判定依据：`idempotency key=... reason=replay|replay-rejection|conflict|stored|stored-rejection`；
- 状态迁移与版本：`state-transition consentId=... CREATED -> AUTHORIZED version=1 APPLIED / REJECTED`；
- 各类拒绝依据：`reason=expired|revoked|cross_partner|rate_limited|insufficient_funds|version_conflict ...`。

> 运行中查看判定日志：`tail -f` 应用日志或直接看 `mvn test` 控制台中
> `[TEST][...]` 与 `c.g.h.o.service.*` 两类输出。

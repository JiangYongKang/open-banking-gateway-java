# 开放银行第三方接入网关（本地可运行）

一个零外部依赖的开放银行网关演示：合作方持令牌在客户同意书（Consent）授权范围内查询账户、
发起支付指令；在**异常与并发**场景下保证状态迁移合法、支付幂等、限流可控、复式账本守恒且可解释。
面向合作方自助对账与客服后台，额外提供：**支付结果按幂等键回查（成功与失败均可查）**、
**同意书"能看/能改"权限分层**、**版本化的一致性读取**。

- Spring Boot 3.4 / Java 21，仅内存存储，不依赖数据库、外部授权服务器或网络资源。
- 令牌、账户、同意书全部在进程内生成，启动即可联调。

## 1. 本地运行

```bash
mvn spring-boot:run
```

启动后：
- 应用端口 `8080`；
- 自动注入演示账户 `acct-1001`(100000 CNY)、`acct-1002`(0)、`acct-2001`(0)；
- 自动签发三个合作方令牌（启动日志与 `GET /local/tokens` 均可查看）：

| 合作方 | 令牌范围 |
| --- | --- |
| `partner-alpha` | `ACCOUNTS_READ` + `PAYMENTS_INITIATE` + `CONSENTS_READ` + `CONSENTS_MANAGE` |
| `partner-beta` | 仅 `ACCOUNTS_READ` |
| `partner-gamma` | 仅 `CONSENTS_READ`（只读，模拟客服后台） |

`/local/**` 仅为本地便利端点（无需认证），不属于 `/api/**` 受保护面。

## 2. HTTP 接口

所有业务接口在 `/api/**` 下，需请求头 `Authorization: Bearer <token>`。

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/consents` | 创建同意书：body `{accountIds, scopes}`（需 `CONSENTS_MANAGE`） |
| `POST /api/consents/{id}/authorize` | 授权（CREATED → AUTHORIZED）（需 `CONSENTS_MANAGE`） |
| `POST /api/consents/{id}/suspend` | 暂停（AUTHORIZED → SUSPENDED）（需 `CONSENTS_MANAGE`） |
| `POST /api/consents/{id}/resume` | 恢复（SUSPENDED → AUTHORIZED）（需 `CONSENTS_MANAGE`） |
| `POST /api/consents/{id}/revoke` | 撤销（任意非终态 → REVOKED）（需 `CONSENTS_MANAGE`） |
| `GET  /api/consents` | 本合作方同意书列表（需 `CONSENTS_READ` 或 `CONSENTS_MANAGE`） |
| `GET  /api/consents/{id}` | 同意书详情（同上；跨合作方与不存在同为 404） |
| `GET  /api/accounts/{accountId}?consentId=` | 授权范围内查询账户余额 |
| `POST /api/payments` | 提交支付，需请求头 `Idempotency-Key` |
| `GET  /api/payments/lookup?idempotencyKey=` | 按幂等键回查首次提交结果（成功或失败） |

同意书响应在原有字段上**新增** `version`、`updatedAt`、`usable`（纯增量，老客户端不受影响）：

```json
{
  "consentId": "...", "status": "AUTHORIZED", "accountIds": ["acct-1001"],
  "scopes": ["ACCOUNTS_READ"], "createdAt": "...", "expiresAt": "...",
  "version": 1, "updatedAt": "...", "usable": true
}
```

支付回查响应：

```json
// 成功：{"idempotencyKey":"pay-001","outcome":"COMPLETED","payment":{...},"recordedAt":"..."}
// 失败：{"idempotencyKey":"pay-002","outcome":"REJECTED","rejectReason":"insufficient_funds",
//        "rejectMessage":"account balance is insufficient","recordedAt":"..."}
```

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

# 按幂等键回查首次提交结果（成功或失败都可查）
curl -s "localhost:8080/api/payments/lookup?idempotencyKey=pay-001" -H "Authorization: Bearer $TOKEN"

# 只读合作方（客服后台）查看自己名下同意书
GAMMA=$(curl -s localhost:8080/local/tokens | jq -r '.[]|select(.partnerId=="partner-gamma")|.token')
curl -s localhost:8080/api/consents -H "Authorization: Bearer $GAMMA"
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
   - 查询账户需 `ACCOUNTS_READ`，支付提交与回查需 `PAYMENTS_INITIATE`；不足 → `403 insufficient_scope`。
2. **同意书级**：业务操作还需同意书处于 `AUTHORIZED`、在有效期内、包含所需 scope
   且账户在授权清单内，否则分别返回 `consent_expired` / `consent_revoked` /
   `invalid_consent_state` / `consent_scope_mismatch` / `account_not_in_consent`。
   创建同意书时申请的 scope 必须是令牌 scope 的子集。

### 同意书"能看 / 能改"分层

- `CONSENTS_READ`：只读。可调用 `GET /api/consents`（列表）与 `GET /api/consents/{id}`（详情），
  响应含 `usable`（当前是否可用）、`updatedAt`（最近一次变更时间）、`version`；
  对创建/授权/暂停/恢复/撤销一律 `403 insufficient_scope`，且在进入状态机之前拒绝，不留痕迹。
- `CONSENTS_MANAGE`：管理。含全部只读能力，可执行全部状态变更。
- **兼容**：不含任何 `CONSENTS_*` 范围的令牌视为分层前的老调用方，保持既有能力
  （可管理同意书），本次分层不会挡住上一轮已在使用的合作方。

**跨合作方访问不泄漏归属**：访问不存在的同意书与访问他人的同意书，对外都是
`404 {"code":"resource_not_found","message":"consent not found: <id>"}`，
状态码、错误码、消息结构完全一致，不因对方是否存在、是否已撤销而不同；
仅内部日志以 `reason=not_found|cross_partner` 区分。列表接口天然只含本合作方数据。

## 5. 幂等、回查与限流

### 幂等与失败回查

- 幂等维度为 `(partnerId, Idempotency-Key)`，不同合作方互不影响。
- 服务端对报文（consentId/from/to/amount/currency）做 SHA-256 指纹：
  - **同键同报文**：直接回放首次结果——成功返回同一支付（同一 paymentId），
    失败原样返回当时判定的可区分原因（如 `insufficient_funds` / `consent_revoked` /
    `rate_limited`），**不重新执行**；
  - **同键异报文**：返回 `409 idempotency_conflict`，不产生任何账务效果；
    首次失败之后同样适用——不能靠改报文把失败绕过去；
  - **业务拒绝**（余额不足、授权不可用、限流等）与成功一样落幂等记录，可按键回查；
    报文校验错误（`validation_error`）与内部错误（`ledger_error`）不落记录，可修正后同键重试。
- 并发同键请求在"键级锁"内串行：一个线程执行，其余等待后回放，保证绝不重复生效。

### 按幂等键回查

`GET /api/payments/lookup?idempotencyKey=<key>`（需 `PAYMENTS_INITIATE`）：

- `outcome=COMPLETED`：带完整支付结果；
- `outcome=REJECTED`：带 `rejectReason`（可区分错误码）与 `rejectMessage`；
- 键不存在（从未提交或已超保留期被清理）→ `404 resource_not_found`；他人的键同样 404。

### 回查记录保留期

回查记录不无限增长：`obg.idempotency.retention`（默认 24h）为保留窗口，
后台任务按 `obg.idempotency.purge-interval`（默认 5min）清理超窗记录。
清理只摘除回查入口本身：**窗口内的回查不受影响，已生效的支付与账务记录永不被清理触碰**。

### 限流

合作方维度滑动窗口，仅对支付提交计数；**被限流的请求不计数、不记账、不改变任何余额**：

- 第 N+1 次超限请求 → `429 rate_limited`（该判定会落幂等记录，可回查）；
- 已存在幂等结果的回放请求直接返回，不受限流影响。

配置项（`application.properties`，均可覆盖）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `obg.consent.default-ttl` | `PT1H` | 同意书默认有效期 |
| `obg.rate-limit.requests-per-window` | `10` | 每合作方每窗口最多支付次数 |
| `obg.rate-limit.window` | `PT1M` | 限流窗口长度 |
| `obg.currency` | `CNY` | 唯一允许币种（不符 → `400 validation_error`） |
| `obg.idempotency.retention` | `PT24H` | 幂等回查记录保留时间 |
| `obg.idempotency.purge-interval` | `PT5M` | 回查记录清理任务间隔 |

其余可区分错误：金额非法/缺幂等键/币种错 → `400 validation_error`；
余额不足 → `422 insufficient_funds`（不对账户做任何改动）。

## 6. 版本、一致性与并发正确性

### 版本与一致性语义

- 每次成功的同意书状态迁移使 `version` 单调递增（只增不减）、`updatedAt` 推进；
  被拒绝的迁移不推进版本。
- 详情/列表读取在同意书对象锁内一次性快照 `status/version/updatedAt/usable`，
  读到的必然是某次成功迁移之前或之后的完整状态，不会撕裂。
- 账户查询、同意书详情、支付判定都基于最近一次成功迁移之后的状态：
  撤销返回成功后提交的支付必按新状态拒绝；查询与撤销并发时，结果二选一且可解释
  （要么按新状态拒绝，要么在状态变更前完整生效）。
- 两个变更并发时在对象锁内二次校验：败者拿到 `409` 冲突提示，
  不会各改各的、互相覆盖；版本数精确等于成功迁移次数。

### 并发正确性与账本解释性

- **支付与撤销互斥**：支付在同意书对象锁内做二次状态判定，撤销与支付在同一把锁上竞争，
  结果只能是"支付完整生效"或"拒绝支付"，不会半生效。
- **账户锁按账户 id 排序获取**，双向并发转账不会死锁。
- **复式记账**：每笔支付写两条分录（付款方 CREDIT / 收款方 DEBIT），
  有借必有贷、借贷必相等；全部分录净额恒为 0，账户总余额恒等于初始总额。
- 扣款→入账→分录分阶段提交，任一步抛错按阶段反向补偿。

## 7. 对既有调用的兼容范围

- 既有端点的路径、请求体、响应原字段、状态码与错误码全部不变；
  同意书响应仅**新增** `version/updatedAt/usable` 三个字段，老客户端按原字段解析不受影响。
- 不含 `CONSENTS_*` 范围的老令牌保持分层前的同意书管理能力，不会被新分层拒绝。
- **一处有意的行为变更**：业务失败（余额不足/授权不可用/限流）的首次提交现在会落幂等记录，
  同键同报文重提将回放首次失败而不是重新执行；同键异报文仍是 `409 idempotency_conflict`。
  需要重试的场景（报文校验错误、内部错误）行为不变，不落记录、可同键重试。

## 8. 本地验证

```bash
# 全部单元/并发/集成测试（34 个用例）
mvn test
```

测试覆盖：

- `ConsentLifecycleTest`：合法/非法状态迁移、过期、撤销、scope 不符、账户越权、跨方 404 同构；
- `PaymentIdempotencyTest`：同键回放、同键异报文冲突、限流、校验失败与余额不足不动账；
- `FailedPaymentLookupTest`：失败提交的回查、充值后同键重提仍回放首次失败、
  失败后同键异报文冲突、保留期清理不触碰支付与账本；
- `PaymentLookupIntegrationTest`（MockMvc）：回查端点成功/失败形态、范围不足 403、未知键 404；
- `ConsentReadOnlyAccessTest`（MockMvc）：只读合作方列表/详情可用、五类变更全拒、
  跨方 404 同构、老调用方能力不变；
- `ConsentVersionConcurrencyTest`：版本单调递增、读取与撤销并发不撕裂、
  双变更并发必有冲突提示、撤销后支付按新状态拒绝；
- `PaymentConcurrencyTest`：32 线程同键并发只生效一次；60 笔双向并发转账总额守恒；
- `RevokeVsPaymentConcurrencyTest`：40 轮 × 8 线程支付与撤销竞速，两种结局都出现，无半生效；
- `SecurityAccessIntegrationTest`（MockMvc 端到端）：无/伪造/过期令牌、范围不足、跨合作方 404 同构。

测试日志同时输出到控制台，包含：
- 幂等键与判定依据：`idempotency key=... reason=replay|replay_rejection|conflict|stored|stored_rejection`；
- 状态迁移与版本：`state-transition consentId=... CREATED -> AUTHORIZED ACCEPTED version=1`；
- 各类拒绝依据：`reason=expired|revoked|cross_partner|rate_limited|insufficient_funds|manage_scope_missing ...`。

> 运行中查看判定日志：`tail -f` 应用日志或直接看 `mvn test` 控制台中
> `[TEST][...]` 与 `c.g.h.o.service.*` 两类输出。

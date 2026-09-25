# 开放银行第三方接入网关（本地可运行）

一个**零外部依赖**的开放银行第三方接入网关：合作方（TPP）在客户授权范围内查询账户、发起支付指令；
在异常与并发场景下保持**状态可预期、账本可解释**。仅使用 Spring Boot Web / Test，内置内存数据，
**不依赖任何外部服务、真实凭据或网络资源**。

## 1. 构建与本地运行

```bash
# JDK 21+，Maven 3.9+
mvn -o package            # 离线构建（依赖已在本地仓库）；联网时可去掉 -o
java -jar target/open-banking-gateway-java-1.0-SNAPSHOT.jar
# 默认端口 8080

# 运行全部测试（含并发重复提交 / 越权访问 / 非法状态迁移）
mvn -o test
```

启动后自动写入种子数据：

| 令牌 | 合作方 | 授权范围（scope） | 用途 |
| --- | --- | --- | --- |
| `token-a-123` | partner-a | `consents` `accounts.read` `payments.create` | 资源属主，全功能 |
| `token-b-456` | partner-b | `consents` `accounts.read` `payments.create` | 与 A 同范围但不拥有其资源，验证**跨合作方无差异拒绝** |
| `token-c-789` | partner-c | `accounts.read` | 验证**令牌范围不足** |

账户：`ACCT-001/ACCT-002`（EUR，余额 1000/500）、`ACCT-003`（GBP，余额 2000），
及清算账户 `CLEARING-EUR` / `CLEARING-GBP`（余额 0）。

## 2. 同意书（Consent）状态机

```
                 authorize                suspend
 AWAITING_AUTHORISATION ───────► AUTHORISED ◄──────► SUSPENDED
        │                            │  ▲                  │
        │  revoke                    │  │     resume       │
        ▼                            ▼  └──────────────────┘
     REVOKED ◄────────────────────────┘
  （终态）
 到达 expiresAt 后懒迁移：AWAITING / AUTHORISED / SUSPENDED ──► EXPIRED（终态）
```

合法迁移：

| 当前状态 | 允许动作 | 目标状态 |
| --- | --- | --- |
| AWAITING_AUTHORISATION | authorize / revoke | AUTHORISED / REVOKED |
| AUTHORISED | suspend / revoke | SUSPENDED / REVOKED |
| SUSPENDED | resume / revoke | AUTHORISED / REVOKED |
| REVOKED | revoke（撤销幂等，保持终态） | REVOKED |
| EXPIRED | 无 | EXPIRED |

其余迁移一律拒绝为 `INVALID_CONSENT_TRANSITION`（HTTP 409）。
过期采用**到达 `expiresAt` 后懒迁移**：任何读取/迁移/使用都会在该同意书锁内先判定过期并迁移到 `EXPIRED`。
重复撤销是幂等的（不报错，保持 REVOKED）。

使用同意书（查账户 / 发起支付）时，按状态给出**可区分**的拒绝原因：

| 情形 | 错误码 | HTTP |
| --- | --- | --- |
| 已撤销 | `CONSENT_REVOKED` | 422 |
| 已过期 | `CONSENT_EXPIRED` | 422 |
| 已暂停 | `CONSENT_SUSPENDED` | 422 |
| 尚未授权 | `CONSENT_NOT_AUTHORIZED` | 422 |
| 同意书 scope 不含所需操作 / 账户不在授权清单 | `CONSENT_SCOPE_MISMATCH` | 403 |

所有针对同一同意书的“读取-判定-迁移/使用”都在**按 consentId 维度的锁**内完成，
因此「撤销与支付同时发生」被串行化：要么支付在撤销前**完整生效**，要么观察到已撤销而拒绝，
**不存在半生效结果**（见 `RevokePaymentConcurrencyTest`，40 轮 × 12 并发支付对 1 个撤销）。

## 3. 授权范围规则（两层 scope + 归属校验）

1. **令牌层（TPP 调用权限）**：`Authorization: Bearer <token>`，由安全拦截器统一校验。
   - 缺失/非法 → `UNAUTHENTICATED`（401）；过期 → `TOKEN_EXPIRED`（401）。两者 HTTP 相同、错误码可区分。
   - 接口所需 scope 不足 → `INSUFFICIENT_SCOPE`（403）。
   - 接口与 scope：同意书写操作 `consents`；查账户 `accounts.read`；发起支付 `payments.create`。
2. **同意书层（客户对该 TPP 的授权）**：同意书必须为 `AUTHORISED`，且其 `scopes` 含操作所需范围
   （查账户 `accounts.read`、支付 `payments`），且目标账户在同意书 `accountIds` 清单内。
3. **合作方归属**：同意书按创建者 partnerId 归属。跨合作方访问他人同意书/账户与“资源不存在”
   **返回完全相同的** `RESOURCE_NOT_FOUND`（404）响应体（仅时间戳不同），不泄漏资源归属；
   真实判定依据只写服务端日志。

## 4. 支付幂等与限流

### 幂等提交

- 请求必须带 `Idempotency-Key` 头；服务端以 **(partnerId, idempotencyKey)** 为复合键。
- 报文指纹：对规范化后的业务字段（consentId/accountId/收方名/收方账号/金额/币种，字段顺序无关）
  计算 SHA-256。
- **同键同报文**：只生效一次，后续请求回放原结果（同一 `paymentId`、`status`），不再次扣款；
  首次成功 HTTP 201，回放 HTTP 200（响应体 `replayed=true`）。终态业务失败（如余额不足）同样被缓存回放。
- **同键不同报文**：`IDEMPOTENCY_CONFLICT`（HTTP 409）。
- 并发重复提交：同一复合键由独立锁串行化「查重 → 处理 → 落记录」，N 并发恰好 1 笔生效、
  其余回放（见 `PaymentIdempotencyTest#concurrentDuplicateSubmissionsApplyExactlyOnce`）。

### 合作方维度限流（令牌桶）

- 每个 partnerId 独立令牌桶；**仅对“新请求”（未命中幂等记录）计限流**，被限流请求在业务处理前拒绝，
  **不触碰余额/账本**；`RATE_LIMITED`（HTTP 429）。
- 限流属于瞬时失败，不写入幂等记录，允许稍后重试。

配置项（`application.properties`，前缀 `obg.gateway`）：

| 配置项 | 默认 | 含义 |
| --- | --- | --- |
| `obg.gateway.consent-ttl-seconds` | `3600` | 同意书默认有效期（秒），到期懒迁移 EXPIRED |
| `obg.gateway.rate-limit-permits-per-minute` | `20` | 每合作方每分钟补充令牌数 |
| `obg.gateway.rate-limit-burst-capacity` | `20` | 每合作方令牌桶容量（突发上限） |

### 加锁顺序（避免死锁）与原子性

支付路径固定按 **幂等键锁 → 同意书锁 → 总账锁** 顺序加锁：

1. 幂等键锁内查重（命中即回放/冲突，无副作用）；
2. 新请求先过限流；
3. 同意书锁内做状态/范围/账户判定（与撤销互斥）；
4. 总账锁内原子完成「余额校验 → 付款方扣款 → 清算账户入账 → 写两条配对账目」。

任何一步抛错，均不会修改余额（余额只在总账锁内、全部校验通过后更新）。

## 5. 账本可解释（复式记账）

- 每笔成功支付写两条不可变条目：付款方 `DEBIT`（金额取负）、清算账户 `CREDIT`（金额取正），金额相等方向相反。
- 全量条目净额恒为 **0**（金额守恒）；付款方净流出 = 清算账户净流入。
- 同一 `paymentId` 防御性禁止重复过账。测试断言：多笔支付后净额为 0、并发下成功笔数 × 2 = 账目条数。

## 6. 统一错误码（对外语义稳定、可区分）

| errorCode | HTTP | 触发场景 |
| --- | --- | --- |
| `UNAUTHENTICATED` | 401 | 无令牌 / 非法令牌 |
| `TOKEN_EXPIRED` | 401 | 令牌过期 |
| `INSUFFICIENT_SCOPE` | 403 | 令牌缺少接口所需 scope |
| `CONSENT_SCOPE_MISMATCH` | 403 | 同意书 scope/账户不匹配 |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在 **或** 跨合作方（无差异） |
| `VALIDATION_ERROR` | 400 | 报文/参数非法 |
| `INVALID_CONSENT_TRANSITION` | 409 | 非法状态迁移 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同幂等键对应不同报文 |
| `CONSENT_EXPIRED` / `CONSENT_REVOKED` / `CONSENT_SUSPENDED` / `CONSENT_NOT_AUTHORIZED` | 422 | 同意书不可用的可区分原因 |
| `INSUFFICIENT_FUNDS` | 422 | 余额不足 |
| `RATE_LIMITED` | 429 | 超过合作方限流 |
| `INTERNAL_ERROR` | 500 | 兜底（不回传内部细节） |

错误响应统一为：
```json
{ "httpStatus": 409, "errorCode": "IDEMPOTENCY_CONFLICT",
  "message": "IDEMPOTENCY_CONFLICT", "timestamp": "..." }
```
对外 `message` 固定为错误码，避免泄漏内部细节；判定依据（幂等键、状态迁移、拒绝理由）只写服务端/测试日志。

## 7. REST 接口速览

| 方法 & 路径 | scope（令牌） | 说明 |
| --- | --- | --- |
| `POST /api/consents` | `consents` | 创建同意书，体：`{"scopes":[...],"accountIds":[...]}` |
| `POST /api/consents/{id}/authorize` | `consents` | 授权 |
| `POST /api/consents/{id}/suspend` | `consents` | 暂停 |
| `POST /api/consents/{id}/resume` | `consents` | 恢复 |
| `POST /api/consents/{id}/revoke` | `consents` | 撤销（幂等） |
| `GET  /api/consents/{id}` | `consents` | 查询（懒判定过期） |
| `GET  /api/accounts/{accountId}?consentId=` | `accounts.read` | 在有效同意书范围内查余额 |
| `POST /api/payments`（头 `Idempotency-Key`） | `payments.create` | 幂等发起支付 |

支付请求体：
```json
{ "consentId": "consent-...", "accountId": "ACCT-001",
  "creditorName": "Shop", "creditorAccount": "EXT-9",
  "amount": "20.00", "currency": "EUR" }
```

## 8. 本地快速验证（curl 示例）

```bash
B=http://localhost:8080
# 1) 创建并授权同意书
CID=$(curl -s -X POST $B/api/consents -H 'Authorization: Bearer token-a-123' \
  -H 'Content-Type: application/json' \
  -d '{"scopes":["accounts.read","payments"],"accountIds":["ACCT-001"]}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["consentId"])')
curl -s -X POST $B/api/consents/$CID/authorize -H 'Authorization: Bearer token-a-123'

# 2) 跨合作方访问：与不存在 ID 返回完全一致的 404
curl -s -i $B/api/consents/$CID -H 'Authorization: Bearer token-b-456'
curl -s -i $B/api/consents/consent-nope -H 'Authorization: Bearer token-b-456'

# 3) 幂等支付（同键同报文两次：201 然后 200 replayed）
curl -i -X POST $B/api/payments -H 'Authorization: Bearer token-a-123' -H 'Idempotency-Key: k-1' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"'$CID'","accountId":"ACCT-001","creditorName":"Shop","creditorAccount":"EXT-9","amount":"20.00","currency":"EUR"}'
curl -i -X POST $B/api/payments -H 'Authorization: Bearer token-a-123' -H 'Idempotency-Key: k-1' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"'$CID'","accountId":"ACCT-001","creditorName":"Shop","creditorAccount":"EXT-9","amount":"20.00","currency":"EUR"}'

# 4) 撤销后再支付 -> CONSENT_REVOKED(422)
curl -s -X POST $B/api/consents/$CID/revoke -H 'Authorization: Bearer token-a-123'
curl -s -i -X POST $B/api/payments -H 'Authorization: Bearer token-a-123' -H 'Idempotency-Key: k-2' \
  -H 'Content-Type: application/json' \
  -d '{"consentId":"'$CID'","accountId":"ACCT-001","creditorName":"Shop","creditorAccount":"EXT-9","amount":"1.00","currency":"EUR"}'
```

## 9. 测试覆盖

| 测试类 | 覆盖点 |
| --- | --- |
| `ConsentStateMachineTest` | 合法/非法迁移、过期懒迁移、暂停/待授权/撤销/范围不符的可区分原因、跨合作方与不存在无差异 |
| `PaymentIdempotencyTest` | 同键只生效一次、同键异报文冲突、限流不扣款、余额不足失败回放、16 线程并发 exactly-once、多笔账目守恒 |
| `RevokePaymentConcurrencyTest` | 40 轮 ×（12 支付线程 vs 1 撤销线程）：无半生效、成功/拒绝计数守恒、成功笔数×2=账目条数、净额 0 |
| `WebApiIntegrationTest` | HTTP 语义：无/非法/过期令牌、范围不足、跨合作方 404 一致性、非法迁移 409、幂等/冲突、12 线程 HTTP 并发只扣一次 |

单测与运行日志会打印 **幂等键、状态迁移（from -> to）与判定依据（verdict / basis / errorCode）**，
关键日志前缀：`consent-transition`、`consent-access`、`payment-result`、`idempotency-replay/conflict`、
`rate-limit`、`ledger-post`、`revoke-vs-pay`。

## 10. 主要代码结构

```
config/    GatewayProperties（TTL/限流配置）、ClockConfig、DataSeeder（种子数据）
security/  TokenService（内存令牌）、SecurityInterceptor、SecuritySupport（scope 判定）
consent/   Consent 状态机、ConsentService（按 consent 锁 + 归属/范围/过期判定）、Controller
account/   账户查询（在同意书锁内准入）
payment/   PaymentService（幂等+限流+锁序）、IdempotencyStore、RateLimiter、Controller
ledger/    LedgerService（总账锁 + 复式记账）、LedgerEntry
web/       统一错误体 ApiError、GlobalExceptionHandler
error/     ErrorCode（错误码↔HTTP）、GatewayException
```

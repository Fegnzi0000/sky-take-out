# MealFlow 智能点餐平台（Sky Take Out）

一个包含用户端与商家端接口的外卖下单系统。后端基于 Spring Boot + MyBatis，结合 Redis 与 RabbitMQ 支持下单、支付、统计报表等业务流程。

## 项目结构

```
sky-take-out/
  pom.xml                      父级 Maven 配置
  sky-common/                  公共常量、工具类、异常、配置等
  sky-pojo/                    DTO/Entity/VO 等数据结构
  sky-server/                  Spring Boot 后端（接口、服务、MQ、定时任务）
```

### 核心模块说明

- `sky-common`：公共常量、上下文、异常、JSON、配置、工具等
- `sky-pojo`：DTO、实体、VO 定义
- `sky-server`：Controller、Service、Mapper、MQ 消费者、定时任务与配置

## 主要功能

- 用户端下单与支付接口
- 商家端订单管理与报表接口
- Redis 缓存与幂等控制
- RabbitMQ 异步下单与支付超时处理
- WebSocket 订单通知

## 本地运行

本仓库为多模块 Maven 项目，启动 `sky-server` 前需在 `sky-server/src/main/resources/application-dev.yml` 中配置 MySQL/Redis/RabbitMQ。

## 异步下单流程（Redis + RabbitMQ）

本项目将“下单写库”从同步事务改造成“缓存预处理 + 消息驱动”，用于削峰、解耦与降低数据库压力。实现位置：

- `sky-server/src/main/java/com/sky/service/impl/OrderServiceImpl.java`
- `sky-server/src/main/java/com/sky/mq/consumer/OrderCreateConsumer.java`
- `sky-server/src/main/java/com/sky/config/RabbitMQConfiguration.java`
- `sky-server/src/main/java/com/sky/mq/consumer/OrderPayTimeoutConsumer.java`

### 为什么这样做

- **削峰**：请求线程不再同步写库，避免高并发时阻塞
- **解耦**：下单接口与落库处理分离，扩展更容易
- **降压**：把大量数据库写操作转移到 MQ 消费端
- **最终一致性**：通过幂等控制与延迟队列保证数据正确

### 用户下单后的执行流程

1. **请求校验与快照**
   - 校验地址与购物车。
   - 生成幂等键（前端传入或后端生成）。
   - 在 Redis 写入幂等记录：`order:idem:{userId}:{idempotencyKey}`。
   - 构建订单快照并写入 Redis：`order:pre:{orderNumber}`。

2. **投递下单消息（异步）**
   - 发送 `OrderCreateMessage` 到 `order.exchange`（routingKey: `order.create`）。

3. **MQ 消费者落库**
   - `OrderCreateConsumer` 先检查 `orders.number` 是否已存在（数据库级幂等）。
   - 不存在则插入 `orders` + `order_detail`，并清空购物车。
   - 写入 `order:result:{orderNumber}`，便于提交线程快速拿到 orderId（最佳努力）。

4. **支付超时处理（延迟队列）**
   - 下单成功后投递 `OrderPayTimeoutMessage` 到延迟队列。
   - TTL 到期后进入 `order.pay.timeout.queue`。
   - `OrderPayTimeoutConsumer` 自动取消未支付订单。

### 幂等与一致性保障

- **请求级幂等**：Redis 记录 `order:idem:{userId}:{idempotencyKey}`，避免重复提交。
- **数据库级幂等**：`orders.number` 加唯一索引，并在消费者中 `getByNumber` + 处理 `DuplicateKeyException`。
- **延迟关闭**：未支付订单自动取消，保证最终一致性。

### 解决了什么问题

- **重复下单**：幂等键防止网络重试和重复点击。
- **高并发写库压力**：下单改为异步落库，缓解数据库压力。
- **强耦合问题**：请求线程只做预处理和投递消息，业务解耦更清晰。
- **超时订单堆积**：延迟队列自动关闭未支付订单。

## 支付流程（微信支付 & 支付宝沙箱）

本项目支付采用“**统一支付入口 + 第三方异步回调驱动订单状态流转**”的方式实现：

- 统一入口：`PUT /user/order/payment`（根据 `payMethod` 选择微信/支付宝）
- 微信回调：`POST /notify/paySuccess`（微信支付 V3，回调报文需解密）
- 支付宝回调：`POST /notify/alipay/notify`（支付宝异步通知，需验签）

> 代码位置：
>
> - 支付入口：`sky-server/src/main/java/com/sky/controller/user/OrderController.java`
> - 支付实现：`sky-server/src/main/java/com/sky/service/impl/OrderServiceImpl.java`
> - 回调接收：`sky-server/src/main/java/com/sky/controller/notify/PayNotifyController.java`
> - 支付宝工具与配置：`sky-common/src/main/java/com/sky/utils/AlipayUtil.java`、`sky-common/src/main/java/com/sky/properties/AlipayProperties.java`
> - 微信工具与配置：`sky-common/src/main/java/com/sky/utils/WeChatPayUtil.java`、`sky-common/src/main/java/com/sky/properties/WeChatProperties.java`

### 0）前置：下单与落库是异步的（支付要兼容“订单未落库”）

用户下单时会先把订单快照写入 Redis（`order:pre:{orderNumber}`），再投递 MQ 让消费者异步写库。
因此出现一种常见时序：**用户已经发起支付/第三方已经回调，但订单可能仍在创建中**。

为此，本项目在支付成功推进状态时，会优先查 DB；若查不到，会尝试从 Redis 的 `order:result:{orderNumber}` 补偿拿到 `orderId` 再查 DB（最佳努力）。

### 1）统一支付入口：用户选择支付方式后怎么执行？

接口：`PUT /user/order/payment`

请求体（`OrdersPaymentDTO`）：

```json
{
  "orderNumber": "xxx",
  "payMethod": 1
}
```

- `payMethod = 1`：微信支付
- `payMethod = 2`：支付宝（沙箱/正式通过 `gatewayUrl` 切换）

服务端返回 `OrderPaymentVO`：

- 微信：返回小程序/JSAPI 调起支付所需字段（`timeStamp`、`nonceStr`、`packageStr`、`paySign`…）
- 支付宝：返回 `payUrl`（跳转 URL）与 `payForm`（HTML 表单，通常自动提交）

### 2）选择【支付宝】后的流程（沙箱）

#### 2.1 发起支付（后端做什么）

`OrderServiceImpl.payment(...)` 检测到 `payMethod=2` 后：

1. 解析应付金额（优先 DB，其次 Redis 快照，兜底 0.01）
2. 使用支付宝 SDK 生成 WAP 支付请求：
   - `payUrl`：适合前端直接跳转
   - `payForm`：适合网页端直接渲染并自动提交

#### 2.2 支付宝怎么回调？

支付宝支付完成后，会以 **HTTP POST 表单参数** 的方式通知 `notify_url`：

- 回调接口：`POST /notify/alipay/notify`
- 协议要求：服务端必须返回纯文本 `success` 或 `failure`

#### 2.3 后端怎么接收？接收后做什么处理？

`PayNotifyController.alipayNotify(...)` 的处理步骤：

1. 从 `HttpServletRequest` 提取回调参数 Map
2. **验签**（核心安全点）：`AlipaySignature.rsaCheckV1(...)`
3. 校验 `trade_status`：仅 `TRADE_SUCCESS / TRADE_FINISHED` 才推进订单
4. 调用 `orderService.paySuccess(out_trade_no)` 推进订单状态为“已支付/待接单”
5. 成功返回 `success`；异常返回 `failure`（让支付宝按策略重试，保证最终一致性）

#### 2.4 为什么要这样做？

- 验签：防止伪造回调导致“假支付成功”
- 幂等：支付宝可能重复通知；网络抖动也会重试
- 回调驱动状态：支付结果以第三方通知为准，避免客户端伪造“已支付”
- 兼容异步下单：回调可能先于落库到达，需要补偿查询

### 3）选择【微信支付】后的流程

#### 3.1 发起支付（后端做什么）

`OrderServiceImpl.payment(...)` 检测到 `payMethod=1` 后：

1. 获取当前登录用户的 `openid`（微信 JSAPI 必需）
2. 调用 `WeChatPayUtil.pay(...)` 生成预支付交易单
3. 返回给前端调起支付所需参数（`OrderPaymentVO`）

#### 3.2 微信怎么回调？

微信支付 V3 支付完成后，会回调你配置的 `notifyUrl`，本项目接收接口为：

- 回调接口：`/notify/paySuccess`

> 注意：真实联调时 `notifyUrl` 必须是**公网可访问**的 HTTP/HTTPS 地址。

#### 3.3 后端怎么接收？接收后做什么处理？

`PayNotifyController.paySuccessNotify(...)` 的处理步骤：

1. 读取回调 body
2. 使用 `apiV3Key` 对回调报文 `resource` 进行 **AES-GCM 解密**
3. 从解密后的 JSON 取 `out_trade_no`（商户订单号）
4. 调用 `orderService.paySuccess(out_trade_no)` 推进订单状态
5. 向微信响应 `{"code":"SUCCESS","message":"SUCCESS"}` 表示已成功处理（否则微信会重试）

#### 3.4 为什么要这样做？

- 回调报文加密：微信 V3 回调需要解密才能取到订单号等字段
- 必须返回 SUCCESS：否则微信会重试通知，导致重复处理压力
- 幂等：微信也会重复通知/重试

### 4）支付宝 vs 微信支付：核心区别（总结对比）

| 对比项 | 支付宝（沙箱/正式） | 微信支付（V3） |
|---|---|---|
| 发起支付返回给前端 | `payUrl` / `payForm`（跳转/表单） | JSAPI 调起参数（`prepay_id` 等） |
| 回调地址 | `POST /notify/alipay/notify` | `/notify/paySuccess` |
| 回调数据安全 | **验签**（RSA2） | **解密**（AES-GCM，`apiV3Key`） |
| 回调协议响应 | 纯文本 `success/failure` | JSON `{"code":"SUCCESS"...}` |
| 幂等必要性 | 必须（可能重复通知） | 必须（可能重复通知/重试） |
| 订单异步落库兼容 | 都需要（可能回调先到） | 都需要（可能回调先到） |

### 5）本地联调配置要点

#### 支付宝沙箱

建议通过环境变量注入密钥（避免提交到仓库），并配置可公网访问的 `notifyUrl`（本地可用内网穿透 ngrok/frp）。

`application-dev.yml` 已提供占位：

- `sky.alipay.gatewayUrl`：默认沙箱网关 `https://openapi-sandbox.dl.alipaydev.com/gateway.do`
- `sky.alipay.appId / merchantPrivateKey / alipayPublicKey`
- `sky.alipay.notifyUrl / returnUrl`

#### 微信支付

微信支付回调地址同样需要公网可访问，并确保 `apiV3Key`、证书路径等配置正确。

## 订单状态机与多支付策略（说明）

> 说明：项目当前已实现 **微信支付 + 支付宝（沙箱）** 的“统一支付入口 + 回调推进状态”。
> 下文的“状态机/策略模式”更多是对设计思路的归纳总结，可按需要继续抽象落地。

### 状态机设计（订单生命周期）

**面试定义**：状态机就是“订单只能按规定路径流转”，每一步都校验前置状态，防止并发或重复请求造成乱序。

**状态集合与含义**（Orders 状态常量）：
- 待付款（PENDING_PAYMENT）：订单已创建，未支付。
- 待接单（TO_BE_CONFIRMED）：支付成功，等待商家确认。
- 已接单（CONFIRMED）：商家已确认接单。
- 派送中（DELIVERY_IN_PROGRESS）：配送进行中。
- 已完成（COMPLETED）：用户已签收/订单结束。
- 已取消（CANCELLED）：用户取消、超时取消等。

**状态流转入口**（典型业务动作）：
- 下单创建 → `PENDING_PAYMENT`
- 支付回调成功 → `TO_BE_CONFIRMED`
- 商家接单 → `CONFIRMED`
- 派送 → `DELIVERY_IN_PROGRESS`
- 完成 → `COMPLETED`
- 取消/超时 → `CANCELLED`

**面试解释要点**：
- 每次状态变更前进行合法性校验（例如：只有“待接单”才能接单）。
- 这样能避免重复请求把订单从“未支付”直接跳到“完成”。
- 状态机不一定要上框架，用常量 + 校验也能清晰可靠。

### 支付回调驱动状态流转

**面试定义**：把“支付是否成功”作为订单状态推进的触发点。支付成功后统一进入回调处理。

**回调处理的核心动作**：
1. 校验回调签名与订单号（防伪造）。
2. 标记订单支付成功（payStatus=PAID）。
3. 将订单状态从“待付款”推进到“待接单”。
4. 推送商家端通知（WebSocket）。

**面试解释要点**：
- 回调只负责“状态推进”，不夹杂复杂业务，便于维护与追踪。
- 回调逻辑做幂等校验，防止重复回调导致重复流转。

### 策略模式封装多支付方式

**面试定义**：策略模式把“支付方式的差异”隔离在不同策略类中，主流程只依赖统一接口。

**典型设计结构**：
- `PayStrategy`：统一接口（pay / verify / callback）
- `WeChatPayStrategy`：微信支付实现（模拟回调）
- `AlipayPayStrategy`：支付宝实现（沙箱验证）
- `PayStrategyFactory`：根据 `payMethod` 返回策略

**面试解释要点**：
- 新增支付方式只新增策略类，不改主流程（符合开闭原则）。
- 业务层不再写 if-else 判断支付方式，代码更干净。
- 支付策略可单独测试、可替换、可扩展。

### 典型支付流程（统一入口）

1. 用户发起支付请求。
2. 服务端读取 `payMethod`。
3. 通过 `PayStrategyFactory` 选择策略。
4. 调用策略进行“下单/验签/回调处理”。
5. 回调成功 → 推进订单状态到“待接单”。

### 面试常见追问与回答要点

- **为什么不用 if-else 直接分支？**
  - 支付方式扩展会导致分支膨胀，策略模式更符合开闭原则。
- **状态机价值？**
  - 保障订单流转有序，避免非法状态跳转，便于审计与排错。
- **回调为什么要做幂等？**
  - 支付回调可能重复通知，必须保证状态只推进一次。

### 这套设计解决的问题

- **流程清晰可控**：状态机保证生命周期有序、可追踪。
- **支付解耦可扩展**：新增支付方式不会影响核心业务。
- **错误可控**：回调失败/重复时不影响订单一致性。

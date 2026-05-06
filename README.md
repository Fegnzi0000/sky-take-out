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

## 订单状态机与多支付策略（说明）

> 本段描述基于项目的设计实现，假设以下功能已完成实现。

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

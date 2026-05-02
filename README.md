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

通过“状态常量 + 状态校验 + 状态流转方法”的方式实现状态机。

- **核心状态**：待付款 → 待接单 → 已接单 → 派送中 → 已完成 / 已取消
- **状态流转入口**：
  - 下单创建：`PENDING_PAYMENT`
  - 支付回调成功：`TO_BE_CONFIRMED`
  - 商家接单：`CONFIRMED`
  - 派送：`DELIVERY_IN_PROGRESS`
  - 完成：`COMPLETED`
  - 取消/超时：`CANCELLED`

**关键点**：每个状态变化前都做状态校验，防止非法跳转，保证流程可控。

**面试解释要点**：
- 状态机的核心是“有序流转 + 非法跳转拦截”。
- 业务方法只允许特定状态进入，例如：只有待接单才能接单、只有派送中才能完成。
- 这样可以避免并发或重复请求导致状态混乱。

### 支付回调驱动状态流转

支付完成后通过回调（微信模拟 / 支付宝沙箱）进入统一回调处理逻辑，核心动作：

1. 校验回调参数与订单号
2. 标记订单支付成功
3. 将订单状态从“待付款”流转到“待接单”
4. 推送商家端通知（WebSocket）

这样“支付是否成功”成为订单状态推进的驱动力，保证流程一致性。

**面试解释要点**：
- 支付回调是订单流转的“触发点”。
- 回调只负责推进状态，不做复杂业务，保证链路清晰。
- 配合幂等校验，避免重复回调导致重复流转。

### 策略模式封装多支付方式

通过策略模式屏蔽不同支付渠道实现细节，实现“同一支付入口 + 多支付实现可插拔”。

**典型设计**：

- `PayStrategy`：支付策略接口（统一 pay / verify / callback 处理）
- `WeChatPayStrategy`：微信支付实现（模拟回调）
- `AlipayPayStrategy`：支付宝实现（沙箱验证）
- `PayStrategyFactory`：根据 `payMethod` 选择策略

**收益**：
- Controller/Service 不关心具体支付实现
- 新增支付方式时只需新增策略类
- 支付逻辑与业务逻辑解耦，便于维护与扩展

**面试解释要点**：
- 策略模式解决“支付方式分支膨胀”的问题。
- 新增支付方式不改主流程代码，只新增策略类并注册工厂。
- 代码更符合开闭原则（对扩展开放，对修改关闭）。

### 典型支付流程（统一入口）

1. 用户提交支付请求 → 服务端读取 `payMethod`
2. 通过 `PayStrategyFactory` 获取具体支付策略
3. 调用对应策略完成支付下单/验签/回调
4. 回调成功后驱动订单状态流转

**面试解释要点**：
- 支付入口统一，策略选择透明。
- 回调处理统一，状态流转集中管理。
- 便于做监控、日志追踪与故障排查。

### 为什么要做这套设计

- **流程可控**：状态机让订单生命周期清晰、可追踪。
- **支付可扩展**：策略模式让多支付接入成本低。
- **风险可隔离**：支付回调和业务流转解耦，失败可控。
- **面试亮点**：体现工程化思维与可扩展架构设计能力。

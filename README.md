# 苍穹外卖（Sky Take Out）

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


package com.sky.mq.consumer;

import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mq.OrderPayTimeoutMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Close unpaid orders after timeout.
 */
@Component
@Slf4j
public class OrderPayTimeoutConsumer {

    private static final String REDIS_MQ_PROCESSED_PREFIX = "order:mq:processed:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @RabbitListener(queues = RabbitMQConfiguration.ORDER_PAY_TIMEOUT_QUEUE)
    public void handle(OrderPayTimeoutMessage message) {
        if (message == null || message.getMessageId() == null) {
            log.warn("OrderPayTimeoutMessage is null or missing messageId");
            return;
        }

        // MQ-level idempotency
        String processedKey = REDIS_MQ_PROCESSED_PREFIX + message.getMessageId();
        Boolean firstTime = stringRedisTemplate.opsForValue().setIfAbsent(processedKey, "1", Duration.ofDays(1));
        if (Boolean.FALSE.equals(firstTime)) {
            return;
        }

        if (message.getOrderId() == null) {
            log.warn("OrderPayTimeoutMessage missing orderId, orderNumber={}", message.getOrderNumber());
            return;
        }

        Orders ordersDB = orderMapper.getById(message.getOrderId());
        if (ordersDB == null) {
            log.warn("Timeout check: order not found, orderId={}, orderNumber={}", message.getOrderId(), message.getOrderNumber());
            return;
        }

        // Only cancel if still unpaid/pending
        if (Orders.PENDING_PAYMENT.equals(ordersDB.getStatus()) && Orders.UN_PAID.equals(ordersDB.getPayStatus())) {
            Orders update = new Orders();
            update.setId(ordersDB.getId());
            update.setStatus(Orders.CANCELLED);
            update.setCancelReason("支付超时，自动取消");
            update.setCancelTime(LocalDateTime.now());
            orderMapper.update(update);

            log.info("Order cancelled by timeout, orderId={}, orderNumber={}", ordersDB.getId(), ordersDB.getNumber());
        }
    }
}


package com.sky.mq.consumer;

import com.alibaba.fastjson.JSON;
import com.sky.config.RabbitMQConfiguration;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mq.OrderCreateMessage;
import com.sky.mq.OrderPayTimeoutMessage;
import com.sky.mq.OrderPreSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MQ consumer that persists orders asynchronously.
 */
@Component
@Slf4j
public class OrderCreateConsumer {

    private static final String REDIS_PRE_KEY_PREFIX = "order:pre:";
    private static final String REDIS_RESULT_KEY_PREFIX = "order:result:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private OrderDetailMapper orderDetailMapper;

    @Autowired
    private ShoppingCartMapper shoppingCartMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfiguration.ORDER_CREATE_QUEUE)
    @Transactional
    public void handle(OrderCreateMessage message) {
        if (message == null || message.getMessageId() == null) {
            log.warn("OrderCreateMessage is null or missing messageId");
            return;
        }

        String orderNumber = message.getOrderNumber();
        if (orderNumber == null || orderNumber.isEmpty()) {
            log.warn("OrderCreateMessage missing orderNumber, messageId={}", message.getMessageId());
            return;
        }

        // DB-level idempotency: if order already exists, short-circuit safely.
        Orders existing = orderMapper.getByNumber(orderNumber);
        if (existing != null) {
            stringRedisTemplate.opsForValue()
                    .set(REDIS_RESULT_KEY_PREFIX + orderNumber, String.valueOf(existing.getId()), Duration.ofHours(1));
            return;
        }

        String preKey = REDIS_PRE_KEY_PREFIX + orderNumber;
        String snapshotJson = stringRedisTemplate.opsForValue().get(preKey);
        if (snapshotJson == null || snapshotJson.isEmpty()) {
            log.warn("Pre-order snapshot missing/expired, orderNumber={}", orderNumber);
            return;
        }

        OrderPreSnapshot snapshot;
        try {
            snapshot = JSON.parseObject(snapshotJson, OrderPreSnapshot.class);
        } catch (Exception e) {
            log.error("Failed to parse pre-order snapshot, orderNumber={}", orderNumber, e);
            return;
        }

        // Build orders
        Orders orders = new Orders();
        BeanUtils.copyProperties(snapshot.getSubmitDTO(), orders);
        orders.setPhone(snapshot.getPhone());
        orders.setAddress(snapshot.getAddress());
        orders.setConsignee(snapshot.getConsignee());
        orders.setNumber(snapshot.getOrderNumber());
        orders.setUserId(snapshot.getUserId());
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setPayStatus(Orders.UN_PAID);
        orders.setOrderTime(snapshot.getOrderTime() == null ? LocalDateTime.now() : snapshot.getOrderTime());

        // Insert order (guard duplicate insert by DB unique constraint)
        try {
            orderMapper.insert(orders);
        } catch (DuplicateKeyException ex) {
            Orders dup = orderMapper.getByNumber(orderNumber);
            if (dup != null) {
                stringRedisTemplate.opsForValue()
                        .set(REDIS_RESULT_KEY_PREFIX + orderNumber, String.valueOf(dup.getId()), Duration.ofHours(1));
            }
            return;
        }

        // Insert details
        List<ShoppingCart> cartItems = snapshot.getCartItems();
        if (cartItems == null || cartItems.isEmpty()) {
            log.warn("Cart snapshot is empty, orderNumber={}", orderNumber);
        } else {
            List<OrderDetail> orderDetailList = new ArrayList<>();
            for (ShoppingCart cart : cartItems) {
                OrderDetail orderDetail = new OrderDetail();
                BeanUtils.copyProperties(cart, orderDetail);
                orderDetail.setOrderId(orders.getId());
                orderDetailList.add(orderDetail);
            }
            orderDetailMapper.insertBatch(orderDetailList);
        }

        // Clear cart in DB
        shoppingCartMapper.deleteByUserId(snapshot.getUserId());

        // Save result for submit-thread best-effort query
        stringRedisTemplate.opsForValue().set(REDIS_RESULT_KEY_PREFIX + orderNumber, String.valueOf(orders.getId()), Duration.ofHours(1));

        // Publish payment-timeout message (delay queue)
        OrderPayTimeoutMessage timeoutMessage = OrderPayTimeoutMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(snapshot.getUserId())
                .orderId(orders.getId())
                .orderNumber(orderNumber)
                .createdAt(LocalDateTime.now())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConfiguration.ORDER_DELAY_EXCHANGE,
                RabbitMQConfiguration.ORDER_PAY_TIMEOUT_DELAY_ROUTING_KEY,
                timeoutMessage
        );

        log.info("Order created asynchronously, orderId={}, orderNumber={}", orders.getId(), orderNumber);
    }
}

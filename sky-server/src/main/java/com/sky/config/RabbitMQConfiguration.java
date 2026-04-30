package com.sky.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for async order processing.
 *
 * <p>
 * 1) order.exchange -> order.create.queue (create order)
 * 2) order.delay.exchange -> order.pay.timeout.delay.queue (TTL) -> DLX -> order.pay.timeout.queue (close unpaid order)
 * </p>
 */
@Configuration
public class RabbitMQConfiguration {

    public static final String ORDER_EXCHANGE = "order.exchange";
    public static final String ORDER_CREATE_QUEUE = "order.create.queue";
    public static final String ORDER_CREATE_ROUTING_KEY = "order.create";

    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    public static final String ORDER_PAY_TIMEOUT_DELAY_QUEUE = "order.pay.timeout.delay.queue";
    public static final String ORDER_PAY_TIMEOUT_DELAY_ROUTING_KEY = "order.pay.timeout.delay";

    public static final String ORDER_DLX_EXCHANGE = "order.dlx.exchange";
    public static final String ORDER_PAY_TIMEOUT_QUEUE = "order.pay.timeout.queue";
    public static final String ORDER_PAY_TIMEOUT_ROUTING_KEY = "order.pay.timeout";

    /** 15 minutes */
    public static final int PAY_TIMEOUT_TTL_MILLIS = 15 * 60 * 1000;

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(ORDER_CREATE_QUEUE).build();
    }

    @Bean
    public Binding orderCreateBinding(DirectExchange orderExchange, Queue orderCreateQueue) {
        return BindingBuilder.bind(orderCreateQueue).to(orderExchange).with(ORDER_CREATE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange orderDlxExchange() {
        return new DirectExchange(ORDER_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderPayTimeoutDelayQueue() {
        return QueueBuilder.durable(ORDER_PAY_TIMEOUT_DELAY_QUEUE)
                .ttl(PAY_TIMEOUT_TTL_MILLIS)
                .deadLetterExchange(ORDER_DLX_EXCHANGE)
                .deadLetterRoutingKey(ORDER_PAY_TIMEOUT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderPayTimeoutDelayBinding(DirectExchange orderDelayExchange, Queue orderPayTimeoutDelayQueue) {
        return BindingBuilder.bind(orderPayTimeoutDelayQueue).to(orderDelayExchange).with(ORDER_PAY_TIMEOUT_DELAY_ROUTING_KEY);
    }

    @Bean
    public Queue orderPayTimeoutQueue() {
        return QueueBuilder.durable(ORDER_PAY_TIMEOUT_QUEUE).build();
    }

    @Bean
    public Binding orderPayTimeoutBinding(DirectExchange orderDlxExchange, Queue orderPayTimeoutQueue) {
        return BindingBuilder.bind(orderPayTimeoutQueue).to(orderDlxExchange).with(ORDER_PAY_TIMEOUT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Make sure @RabbitListener can convert JSON messages into our DTO classes.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        return factory;
    }
}



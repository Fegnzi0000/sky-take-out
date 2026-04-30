package com.sky.mq;

import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.ShoppingCart;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Snapshot of an order at submit-time, stored in Redis and consumed by MQ consumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPreSnapshot implements Serializable {

    private Long userId;

    private String orderNumber;

    private LocalDateTime orderTime;

    private OrdersSubmitDTO submitDTO;

    /** Address snapshot */
    private String consignee;
    private String phone;
    private String address;

    /** Shopping cart snapshot */
    private List<ShoppingCart> cartItems;
}


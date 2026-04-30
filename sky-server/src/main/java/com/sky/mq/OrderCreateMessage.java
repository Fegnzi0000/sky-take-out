package com.sky.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Message to create an order asynchronously.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateMessage implements Serializable {

	/** Used for MQ consumer idempotency */
	private String messageId;

	private Long userId;

	/** orders.number */
	private String orderNumber;

	/** client-side idempotency key (optional) */
	private String idempotencyKey;

	private LocalDateTime createdAt;
}



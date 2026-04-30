package com.sky.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Message published to a delay queue to close unpaid orders after a timeout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPayTimeoutMessage implements Serializable {

	private String messageId;

	private Long userId;

	/** orders.id */
	private Long orderId;

	/** orders.number */
	private String orderNumber;

	private LocalDateTime createdAt;
}



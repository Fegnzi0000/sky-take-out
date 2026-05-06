package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Alipay configuration.
 *
 * <p>Supports both sandbox and production environments; switch via gatewayUrl.</p>
 */
@Component
@ConfigurationProperties(prefix = "sky.alipay")
@Data
public class AlipayProperties {

    /** Alipay appId (开放平台应用ID) */
    private String appId;

    /** Merchant private key (PKCS8, RSA2) */
    private String merchantPrivateKey;

    /** Alipay public key (used to verify signature) */
    private String alipayPublicKey;

    /** Alipay gateway, e.g. https://openapi-sandbox.dl.alipaydev.com/gateway.do */
    private String gatewayUrl;

    /** Async notification URL (notify_url) */
    private String notifyUrl;

    /** Sync return URL (return_url), optional */
    private String returnUrl;

    /** Charset, default utf-8 */
    private String charset = "utf-8";

    /** Sign type, default RSA2 */
    private String signType = "RSA2";
}


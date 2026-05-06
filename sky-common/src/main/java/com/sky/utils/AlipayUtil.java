package com.sky.utils;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.alipay.api.internal.util.AlipaySignature;
import com.alibaba.fastjson.JSONObject;
import com.sky.properties.AlipayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Alipay helper for sandbox/production.
 */
@Component
@Slf4j
public class AlipayUtil {

    @Autowired
    private AlipayProperties alipayProperties;

    private AlipayClient getClient() {
        return new DefaultAlipayClient(
                alipayProperties.getGatewayUrl(),
                alipayProperties.getAppId(),
                alipayProperties.getMerchantPrivateKey(),
                "json",
                alipayProperties.getCharset(),
                alipayProperties.getAlipayPublicKey(),
                alipayProperties.getSignType()
        );
    }

    /**
     * Create an Alipay WAP pay form (HTML) that auto-submits.
     */
    public String createWapPayForm(String outTradeNo, String totalAmount, String subject) throws AlipayApiException {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setNotifyUrl(alipayProperties.getNotifyUrl());
        request.setReturnUrl(alipayProperties.getReturnUrl());

        JSONObject biz = new JSONObject();
        biz.put("out_trade_no", outTradeNo);
        biz.put("total_amount", totalAmount);
        biz.put("subject", subject);
        biz.put("product_code", "QUICK_WAP_WAY");
        request.setBizContent(biz.toJSONString());

        AlipayTradeWapPayResponse response = getClient().pageExecute(request);
        return response.getBody();
    }

    /**
     * Create an Alipay WAP pay url (GET). Some clients prefer redirect URL over HTML form.
     */
    public String createWapPayUrl(String outTradeNo, String totalAmount, String subject) throws AlipayApiException {
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setNotifyUrl(alipayProperties.getNotifyUrl());
        request.setReturnUrl(alipayProperties.getReturnUrl());

        JSONObject biz = new JSONObject();
        biz.put("out_trade_no", outTradeNo);
        biz.put("total_amount", totalAmount);
        biz.put("subject", subject);
        biz.put("product_code", "QUICK_WAP_WAY");
        request.setBizContent(biz.toJSONString());

        AlipayTradeWapPayResponse response = getClient().pageExecute(request, "GET");
        return response.getBody();
    }

    /**
     * Verify Alipay signature.
     */
    public boolean verifyNotifySignature(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        try {
            return AlipaySignature.rsaCheckV1(
                    params,
                    alipayProperties.getAlipayPublicKey(),
                    alipayProperties.getCharset(),
                    alipayProperties.getSignType()
            );
        } catch (AlipayApiException e) {
            log.error("Alipay signature verify failed", e);
            return false;
        }
    }

    /**
     * Alipay notify parameters are sometimes URL-encoded; decode a single value safely.
     */
    public String urlDecode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}



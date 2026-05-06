package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderPaymentVO implements Serializable {

    /** 支付方式：1微信，2支付宝 */
    private Integer payMethod;

    private String nonceStr; //随机字符串
    private String paySign; //签名
    private String timeStamp; //时间戳
    private String signType; //签名算法
    private String packageStr; //统一下单接口返回的 prepay_id 参数值

    /** 支付宝：跳转URL（GET方式） */
    private String payUrl;

    /** 支付宝：HTML form（POST方式，通常会自动提交） */
    private String payForm;

}

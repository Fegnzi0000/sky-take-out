package com.sky.config;

import com.sky.properties.AliOssProperties;
import com.sky.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置类，用于创建AliOssUtil对象，并将其注册到Spring容器中，以便在应用程序中使用阿里云OSS服务进行文件上传和管理。
 * 该类使用了@Configuration注解，表示这是一个配置类，Spring会自动扫描并加载其中定义的Bean。
 * aliOssUtil方法使用了@Bean注解，
 * 表示这是一个Bean定义方法，Spring会调用该方法来创建AliOssUtil对象，并将其注册到Spring容器中。
 * @ConditionalOnMissingBean注解表示只有当容器
 */
@Configuration
@Slf4j
public class OssConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AliOssUtil aliOssUtil(AliOssProperties aliOssProperties) {
        log.info("开始创建阿里云文件上传工具类对象：{}", aliOssProperties);
        return new AliOssUtil(aliOssProperties.getEndpoint(),
                aliOssProperties.getAccessKeyId(),
                aliOssProperties.getAccessKeySecret(),
                aliOssProperties.getBucketName());
    };

}

package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("sky.redis")
@Data
public class RedisProperties {
    private String host;
    private Integer port;
    private String password;
    private Integer database;
}

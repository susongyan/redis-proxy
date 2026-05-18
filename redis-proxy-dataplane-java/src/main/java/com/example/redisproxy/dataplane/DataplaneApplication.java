package com.example.redisproxy.dataplane;

import com.example.redisproxy.dataplane.config.ProxyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(ProxyProperties.class)
public class DataplaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataplaneApplication.class, args);
    }
}

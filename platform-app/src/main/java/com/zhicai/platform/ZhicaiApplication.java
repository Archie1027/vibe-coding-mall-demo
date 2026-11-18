package com.zhicai.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class ZhicaiApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ZhicaiApplication.class, args);
        if (java.util.Arrays.asList(context.getEnvironment().getActiveProfiles()).contains("dev-bootstrap")) context.close();
    }
}

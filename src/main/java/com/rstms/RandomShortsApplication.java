package com.rstms;

import com.rstms.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class RandomShortsApplication {
    public static void main(String[] args) {
        SpringApplication.run(RandomShortsApplication.class, args);
    }
}

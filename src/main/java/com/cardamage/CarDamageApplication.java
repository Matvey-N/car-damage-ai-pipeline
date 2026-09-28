package com.cardamage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class CarDamageApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarDamageApplication.class, args);
    }
}

package com.hnp.backendofflinefirst;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendOfflineFirstApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendOfflineFirstApplication.class, args);
    }

}

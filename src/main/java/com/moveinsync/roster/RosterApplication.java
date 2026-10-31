package com.moveinsync.roster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RosterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RosterApplication.class, args);
    }
}

package com.example.taacsample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Nothing here wires up admission control — the starter on the classpath
 * does it, driven entirely by {@code application.yml}'s {@code taac.*} block.
 */
@SpringBootApplication
public class TaacSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaacSampleApplication.class, args);
    }
}

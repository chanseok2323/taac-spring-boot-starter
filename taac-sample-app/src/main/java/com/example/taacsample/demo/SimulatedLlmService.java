package com.example.taacsample.demo;

import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

/** Stand-in for a real LLM call — sleeps in rough proportion to input length. */
@Service
public class SimulatedLlmService {

    public String call(String prompt) {
        long sleepMs = Math.min(5_000L, Math.max(20L, prompt.length() * 5L));
        long jitter = ThreadLocalRandom.current().nextLong(-50, 100);
        try {
            Thread.sleep(Math.max(1L, sleepMs + jitter));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        return "echo(" + prompt.length() + " chars): " + prompt;
    }
}

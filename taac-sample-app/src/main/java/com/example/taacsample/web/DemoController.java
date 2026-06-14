package com.example.taacsample.web;

import com.example.taacsample.demo.AdmissionGuardedService;
import io.github.chanseok.taac.metrics.AdmissionMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 *   GET /demo/ask?q=hello
 *   GET /demo/metrics
 * </pre>
 */
@RestController
public class DemoController {

    private final AdmissionGuardedService guarded;
    private final AdmissionMetrics metrics;

    public DemoController(AdmissionGuardedService guarded, AdmissionMetrics metrics) {
        this.guarded = guarded;
        this.metrics = metrics;
    }

    @GetMapping("/demo/ask")
    public String ask(@RequestParam(name = "q", defaultValue = "hello") String q) {
        return guarded.guarded(q);
    }

    @GetMapping("/demo/metrics")
    public AdmissionMetrics.MetricsSnapshot metrics() {
        return metrics.snapshot();
    }
}

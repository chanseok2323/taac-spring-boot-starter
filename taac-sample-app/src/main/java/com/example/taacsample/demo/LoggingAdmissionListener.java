package com.example.taacsample.demo;

import io.github.chanseok.taac.admission.AdmissionEvent;
import io.github.chanseok.taac.admission.AdmissionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Example of the listener SPI — just registering a bean is enough,
 * the autoconfiguration picks it up alongside the metrics listener.
 */
@Component
public class LoggingAdmissionListener implements AdmissionListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingAdmissionListener.class);

    @Override
    public void onAcquireFailed(AdmissionEvent.AcquireFailed event) {
        log.warn("admission failed: weight={} reason={} detail={}",
                event.weight(), event.reason(), event.detail());
    }

    @Override
    public void onCompletion(AdmissionEvent.Completed event) {
        if (log.isDebugEnabled()) {
            log.debug("work done in {} ms (in={} out={} tokens)",
                    event.responseTimeMs(), event.inputTokens(), event.outputTokens());
        }
    }
}

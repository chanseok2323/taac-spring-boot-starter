package com.example.taacsample.demo;

import io.github.chanseok.taac.support.AdmissionTemplate;
import org.springframework.stereotype.Service;

/**
 * Day-to-day usage: hand the work to {@link AdmissionTemplate} and let it
 * take care of acquire / completion / release.
 */
@Service
public class AdmissionGuardedService {

    private final AdmissionTemplate admission;
    private final SimulatedLlmService llm;

    public AdmissionGuardedService(AdmissionTemplate admission, SimulatedLlmService llm) {
        this.admission = admission;
        this.llm = llm;
    }

    public String guarded(String prompt) {
        return admission.execute(prompt, llm::call);
    }
}

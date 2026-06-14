package com.example.taacsample;

import io.github.chanseok.taac.admission.AdmissionController;
import io.github.chanseok.taac.support.AdmissionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TaacSampleApplicationTests {

    @Autowired AdmissionController controller;
    @Autowired AdmissionTemplate   template;

    @Test
    void context_loads_with_default_vegas_policy() {
        assertThat(controller).isNotNull();
        assertThat(template).isNotNull();
    }
}

package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;

/**
 * 고정 permit 정책 (비교 실험용 기준선).
 *
 * 어떤 신호도 사용하지 않고 항상 maxConcurrency를 반환한다.
 * Baseline과 제안 기법 사이의 중간 비교군으로 사용:
 *
 *   - Baseline: Admission Control 없음 (무제한)
 *   - Fixed:    고정된 동시성 제한 (제어 있음, 적응 없음)
 *   - Adaptive: 응답시간·힙 기반 동적 제어 (제어 + 적응)
 *   - AIMD:     TCP 혼잡 제어 응용
 *
 * Fixed와 Adaptive를 비교하면 "적응 기능 자체의 기여"를 측정할 수 있다.
 */
public class FixedConcurrencyPolicy implements ConcurrencyPolicy {

    private final int maxConcurrency;

    public FixedConcurrencyPolicy(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public int evaluate(GateState state) {
        return maxConcurrency;
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return MemoryPressureLevel.LOW;
    }
}

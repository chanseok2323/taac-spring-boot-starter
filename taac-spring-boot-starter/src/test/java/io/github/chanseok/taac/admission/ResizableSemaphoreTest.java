package io.github.chanseok.taac.admission;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResizableSemaphoreTest {

    @Test
    void addPermits_grows_logical_capacity_and_available() {
        var sem = new ResizableSemaphore(5, false);

        sem.addPermits(3);

        assertThat(sem.logicalCapacity()).isEqualTo(8);
        assertThat(sem.availablePermits()).isEqualTo(8);
    }

    @Test
    void reducePermits_shrinks_logical_capacity() {
        var sem = new ResizableSemaphore(10, false);

        sem.reducePermits(4);

        assertThat(sem.logicalCapacity()).isEqualTo(6);
    }

    @Test
    void shrinking_below_in_flight_creates_debt_that_release_pays_down() {
        var sem = new ResizableSemaphore(5, false);
        sem.acquireUninterruptibly(3);   // 3 in-flight, 2 available

        sem.reducePermits(4);            // capacity 1, available -2

        assertThat(sem.logicalCapacity()).isEqualTo(1);
        assertThat(sem.borrowedPermits()).isEqualTo(2);

        sem.release();
        assertThat(sem.borrowedPermits()).isEqualTo(1);
        sem.release();
        assertThat(sem.borrowedPermits()).isZero();
        sem.release();
        assertThat(sem.availablePermits()).isOne();
    }

    @Test
    void reducePermits_more_than_capacity_throws() {
        var sem = new ResizableSemaphore(3, false);

        assertThatThrownBy(() -> sem.reducePermits(5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negative_arguments_throw_on_both_directions() {
        var sem = new ResizableSemaphore(5, false);

        assertThatThrownBy(() -> sem.reducePermits(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sem.addPermits(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}

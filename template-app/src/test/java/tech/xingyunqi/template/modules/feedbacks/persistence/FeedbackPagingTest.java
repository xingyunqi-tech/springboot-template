package tech.xingyunqi.template.modules.feedbacks.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeedbackPagingTest {

    @Test
    void offsetAndLimitRoundHalfUp() {
        assertThat(FeedbackRepositoryImpl.pgBigint(1.5)).isEqualTo(2);
        assertThat(FeedbackRepositoryImpl.pgBigint(0.3)).isZero();
        assertThat(FeedbackRepositoryImpl.pgBigint(10)).isEqualTo(10);
    }

    @Test
    void lazyTotalWhenPageIsNotFull() {
        assertThat(FeedbackRepositoryImpl.lazyTotal(1, 1.5, 1.5)).isEqualTo(2.5);
        assertThat(FeedbackRepositoryImpl.lazyTotal(0, 0, 10)).isZero();
        assertThat(FeedbackRepositoryImpl.lazyTotal(3, 0, 10)).isEqualTo(3);
    }

    @Test
    void countRequiredWhenPageIsFullOrEmptyBeyondFirst() {
        assertThat(FeedbackRepositoryImpl.lazyTotal(10, 0, 10)).isNull();
        assertThat(FeedbackRepositoryImpl.lazyTotal(0, 20, 10)).isNull();
    }
}

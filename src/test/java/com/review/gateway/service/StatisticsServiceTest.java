package com.review.gateway.service;

import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.MetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class StatisticsServiceTest {

    private ReviewRepository.StatusCount statusCount(ReviewStatus status, long total) {
        return new ReviewRepository.StatusCount() {
            @Override
            public ReviewStatus getStatus() {
                return status;
            }

            @Override
            public Long getTotal() {
                return total;
            }
        };
    }

    @Test
    void aggregatesEveryMetricFromTheRepositories() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        ReviewJobRepository reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        ReviewCommentRepository reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        ReviewEventRepository reviewEventRepository = Mockito.mock(ReviewEventRepository.class);

        when(reviewRepository.countByStatusGrouped()).thenReturn(List.of(
                statusCount(ReviewStatus.QUEUED, 3L),
                statusCount(ReviewStatus.RUNNING, 2L),
                statusCount(ReviewStatus.PUBLISHED, 10L)));
        when(reviewJobRepository.averageQueueWaitMillis()).thenReturn(1234.5);
        when(reviewJobRepository.averageRunDurationMillis()).thenReturn(67890.0);
        when(reviewCommentRepository.count()).thenReturn(42L);
        when(reviewEventRepository.countByEventType(EventType.RETRY)).thenReturn(7L);

        StatisticsService service = new StatisticsService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository);

        MetricsSnapshot snapshot = service.computeMetrics();

        assertThat(snapshot.total()).isEqualTo(15);
        assertThat(snapshot.byStatus()).containsEntry(ReviewStatus.QUEUED, 3L);
        assertThat(snapshot.byStatus()).containsEntry(ReviewStatus.RUNNING, 2L);
        assertThat(snapshot.byStatus()).containsEntry(ReviewStatus.PUBLISHED, 10L);
        assertThat(snapshot.avgQueueMs()).isEqualTo(1234.5);
        assertThat(snapshot.avgRunMs()).isEqualTo(67890.0);
        assertThat(snapshot.totalComments()).isEqualTo(42L);
        assertThat(snapshot.retries()).isEqualTo(7L);
    }

    @Test
    void nullAveragesFromTheRepositoryBecomeZero() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        ReviewJobRepository reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        ReviewCommentRepository reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        ReviewEventRepository reviewEventRepository = Mockito.mock(ReviewEventRepository.class);

        when(reviewRepository.countByStatusGrouped()).thenReturn(List.of());
        when(reviewJobRepository.averageQueueWaitMillis()).thenReturn(null);
        when(reviewJobRepository.averageRunDurationMillis()).thenReturn(null);
        when(reviewCommentRepository.count()).thenReturn(0L);
        when(reviewEventRepository.countByEventType(EventType.RETRY)).thenReturn(0L);

        StatisticsService service = new StatisticsService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository);

        MetricsSnapshot snapshot = service.computeMetrics();

        assertThat(snapshot.total()).isZero();
        assertThat(snapshot.avgQueueMs()).isZero();
        assertThat(snapshot.avgRunMs()).isZero();
    }
}

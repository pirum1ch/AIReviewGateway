package com.review.gateway.service;

import com.review.gateway.model.Backend;
import com.review.gateway.model.enums.BackendStatus;
import com.review.gateway.model.enums.EventType;
import com.review.gateway.model.enums.ReviewStatus;
import com.review.gateway.repository.BackendRepository;
import com.review.gateway.repository.ReviewCommentRepository;
import com.review.gateway.repository.ReviewEventRepository;
import com.review.gateway.repository.ReviewJobRepository;
import com.review.gateway.repository.ReviewRepository;
import com.review.gateway.service.dto.BackendSnapshot;
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

    private StatisticsService newService(ReviewRepository reviewRepository,
                                          ReviewJobRepository reviewJobRepository,
                                          ReviewCommentRepository reviewCommentRepository,
                                          ReviewEventRepository reviewEventRepository,
                                          BackendRepository backendRepository) {
        return new StatisticsService(reviewRepository, reviewJobRepository, reviewCommentRepository,
                reviewEventRepository, backendRepository);
    }

    @Test
    void aggregatesEveryMetricFromTheRepositories() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        ReviewJobRepository reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        ReviewCommentRepository reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        ReviewEventRepository reviewEventRepository = Mockito.mock(ReviewEventRepository.class);
        BackendRepository backendRepository = Mockito.mock(BackendRepository.class);

        when(reviewRepository.countByStatusGrouped()).thenReturn(List.of(
                statusCount(ReviewStatus.QUEUED, 3L),
                statusCount(ReviewStatus.RUNNING, 2L),
                statusCount(ReviewStatus.PUBLISHED, 10L)));
        when(reviewJobRepository.averageQueueWaitMillis()).thenReturn(1234.5);
        when(reviewJobRepository.averageRunDurationMillis()).thenReturn(67890.0);
        when(reviewCommentRepository.count()).thenReturn(42L);
        when(reviewEventRepository.countByEventType(EventType.RETRY)).thenReturn(7L);

        StatisticsService service = newService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository, backendRepository);

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
        BackendRepository backendRepository = Mockito.mock(BackendRepository.class);

        when(reviewRepository.countByStatusGrouped()).thenReturn(List.of());
        when(reviewJobRepository.averageQueueWaitMillis()).thenReturn(null);
        when(reviewJobRepository.averageRunDurationMillis()).thenReturn(null);
        when(reviewCommentRepository.count()).thenReturn(0L);
        when(reviewEventRepository.countByEventType(EventType.RETRY)).thenReturn(0L);

        StatisticsService service = newService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository, backendRepository);

        MetricsSnapshot snapshot = service.computeMetrics();

        assertThat(snapshot.total()).isZero();
        assertThat(snapshot.avgQueueMs()).isZero();
        assertThat(snapshot.avgRunMs()).isZero();
    }

    @Test
    void listBackendsMapsEachBackendWithItsRunningCount() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        ReviewJobRepository reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        ReviewCommentRepository reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        ReviewEventRepository reviewEventRepository = Mockito.mock(ReviewEventRepository.class);
        BackendRepository backendRepository = Mockito.mock(BackendRepository.class);

        Backend backend = new Backend("mac-mini-1", "https://mac-mini-1.local", "model-x", 2);
        backend.setStatus(BackendStatus.ACTIVE);
        when(backendRepository.findAll()).thenReturn(List.of(backend));
        when(reviewJobRepository.countRunningJobsForBackend(backend.getId())).thenReturn(1L);

        StatisticsService service = newService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository, backendRepository);

        List<BackendSnapshot> snapshots = service.listBackends();

        assertThat(snapshots).hasSize(1);
        BackendSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.name()).isEqualTo("mac-mini-1");
        assertThat(snapshot.model()).isEqualTo("model-x");
        assertThat(snapshot.capacity()).isEqualTo(2);
        assertThat(snapshot.status()).isEqualTo(BackendStatus.ACTIVE);
        assertThat(snapshot.running()).isEqualTo(1L);
    }

    @Test
    void listBackendsReturnsEmptyListWhenNoneRegistered() {
        ReviewRepository reviewRepository = Mockito.mock(ReviewRepository.class);
        ReviewJobRepository reviewJobRepository = Mockito.mock(ReviewJobRepository.class);
        ReviewCommentRepository reviewCommentRepository = Mockito.mock(ReviewCommentRepository.class);
        ReviewEventRepository reviewEventRepository = Mockito.mock(ReviewEventRepository.class);
        BackendRepository backendRepository = Mockito.mock(BackendRepository.class);

        when(backendRepository.findAll()).thenReturn(List.of());

        StatisticsService service = newService(
                reviewRepository, reviewJobRepository, reviewCommentRepository, reviewEventRepository, backendRepository);

        assertThat(service.listBackends()).isEmpty();
    }
}

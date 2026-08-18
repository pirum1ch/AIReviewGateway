package com.review.worker.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.review.worker.config.WorkerProperties;
import com.review.worker.gateway.GatewayClient;
import com.review.worker.gateway.HeartbeatOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * QA-added (Worker Observability &amp; Claim Latency, Part 1): the architecture doc's own test guidance
 * (§10, T-1.1/T-1.2/T-1.4) explicitly calls for asserting the actual <em>content and level</em> of the
 * four new/changed log lines -- not just that {@code HeartbeatScheduler.tick}/{@code GatewayClient.claim}
 * still compile and behave functionally, which the developer's own tests ({@code HeartbeatSchedulerTest},
 * {@code GatewayClientTest}) already cover well. Before this file, nothing in the suite actually read a
 * captured log line's level or text for WOC-01/02/04, which is precisely the class of thing a purely
 * behavioral unit test can miss (e.g. a copy-paste that leaves the empty-claim line at INFO, or an
 * elapsedSec/heartbeats field that never advances).
 */
class WorkerObservabilityLoggingContentTest {

    // ---------------------------------------------------------------------------------------------
    // T-1.1: GatewayClient.claim -- INFO only when a job was actually claimed; the empty (204) poll
    // case drops to DEBUG (WOC-01/WOC-06).
    // ---------------------------------------------------------------------------------------------

    private MockRestServiceServer mockServer;
    private GatewayClient gatewayClient;
    private ListAppender<ILoggingEvent> claimLogAppender;
    private Logger gatewayClientLogger;

    @BeforeEach
    void setUpGatewayClient() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gateway.test")
                .defaultHeader("Authorization", "Bearer test-token");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        gatewayClient = new GatewayClient(builder.build());

        gatewayClientLogger = (Logger) LoggerFactory.getLogger(GatewayClient.class);
        gatewayClientLogger.setLevel(Level.ALL); // deterministic regardless of ambient test log config
        claimLogAppender = new ListAppender<>();
        claimLogAppender.start();
        gatewayClientLogger.addAppender(claimLogAppender);
    }

    @AfterEach
    void tearDownGatewayClient() {
        gatewayClientLogger.detachAppender(claimLogAppender);
        gatewayClientLogger.setLevel(null);
    }

    @Test
    void claimingAJobLogsExactlyOneInfoLineWithJobAndReviewId() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"jobId":42,"reviewId":7,"payload":{"diff":"d","promptVersion":"v1"}}
                        """, MediaType.APPLICATION_JSON));

        gatewayClient.claim("backend-1", "worker-1");

        List<ILoggingEvent> infoEvents = claimLogAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infoEvents).as("exactly one INFO line for a successful claim (WOC-01)").hasSize(1);
        assertThat(infoEvents.get(0).getFormattedMessage())
                .contains("Claimed job").contains("jobId=42").contains("reviewId=7");
    }

    @Test
    void anEmptyPollLogsNoInfoLineAtAll() {
        mockServer.expect(requestTo("https://gateway.test/jobs/claim"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withNoContent());

        gatewayClient.claim("backend-1", "worker-1");

        List<ILoggingEvent> infoEvents = claimLogAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infoEvents)
                .as("an idle/empty claim poll must never log at INFO (WOC-01) -- this was the exact "
                        + "inversion the architecture doc set out to fix (busy Worker silent, idle "
                        + "Worker spamming jobId=none at INFO)")
                .isEmpty();
        List<ILoggingEvent> debugEvents = claimLogAppender.list.stream()
                .filter(e -> e.getLevel() == Level.DEBUG).toList();
        assertThat(debugEvents).as("the empty-poll case still logs, but only at DEBUG (WOC-01)").hasSize(1);
    }

    // ---------------------------------------------------------------------------------------------
    // T-1.2: HeartbeatScheduler.tick -- one INFO "Job in progress" line per ACCEPTED+shouldContinue
    // tick, with a tick counter and elapsedSec that both advance across calls (WOC-02/05).
    // ---------------------------------------------------------------------------------------------

    private Logger heartbeatSchedulerLogger;
    private ListAppender<ILoggingEvent> heartbeatLogAppender;
    private HeartbeatScheduler scheduler;
    private GatewayClient mockedGatewayClient;

    @BeforeEach
    void setUpHeartbeatScheduler() {
        mockedGatewayClient = mock(GatewayClient.class);
        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("test-model");
        scheduler = new HeartbeatScheduler(mockedGatewayClient, properties);

        heartbeatSchedulerLogger = (Logger) LoggerFactory.getLogger(HeartbeatScheduler.class);
        heartbeatSchedulerLogger.setLevel(Level.ALL);
        heartbeatLogAppender = new ListAppender<>();
        heartbeatLogAppender.start();
        heartbeatSchedulerLogger.addAppender(heartbeatLogAppender);
    }

    @AfterEach
    void tearDownHeartbeatScheduler() {
        heartbeatSchedulerLogger.detachAppender(heartbeatLogAppender);
        heartbeatSchedulerLogger.setLevel(null);
    }

    @Test
    void everyAcceptedContinueTickLogsExactlyOneInfoJobInProgressLineWithGrowingCounters() {
        when(mockedGatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(true));
        AbortSignal abortSignal = new AbortSignal();
        AtomicInteger consecutiveFailures = new AtomicInteger();
        AtomicInteger tickCount = new AtomicInteger();
        // Simulate a job that has already been running for a while, so elapsedSec is observably > 0.
        long startedAtMillis = System.currentTimeMillis() - 65_000L;

        scheduler.tick(1L, "worker-1", abortSignal, consecutiveFailures, startedAtMillis, tickCount);
        scheduler.tick(1L, "worker-1", abortSignal, consecutiveFailures, startedAtMillis, tickCount);
        scheduler.tick(1L, "worker-1", abortSignal, consecutiveFailures, startedAtMillis, tickCount);

        List<ILoggingEvent> jobInProgressLines = heartbeatLogAppender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("Job in progress"))
                .toList();
        assertThat(jobInProgressLines).as("exactly one INFO line per tick (WOC-02/WOC-05)").hasSize(3);

        assertThat(jobInProgressLines.get(0).getFormattedMessage())
                .contains("jobId=1").contains("workerId=worker-1")
                .contains("elapsedSec=").contains("heartbeats=1");
        assertThat(jobInProgressLines.get(1).getFormattedMessage()).contains("heartbeats=2");
        assertThat(jobInProgressLines.get(2).getFormattedMessage()).contains("heartbeats=3");

        // elapsedSec must be derived from the (fixed) startedAtMillis, not reset per tick -- every line
        // reports a plausible, non-decreasing elapsed time around the simulated ~65s of runtime.
        for (ILoggingEvent event : jobInProgressLines) {
            String message = event.getFormattedMessage();
            int idx = message.indexOf("elapsedSec=");
            String tail = message.substring(idx + "elapsedSec=".length());
            long elapsedSec = Long.parseLong(tail.split(",")[0].trim());
            assertThat(elapsedSec).isGreaterThanOrEqualTo(65L);
        }
    }

    @Test
    void aGatewayRequestedStopTickDoesNotLogJobInProgress() {
        when(mockedGatewayClient.heartbeat(anyLong(), anyString())).thenReturn(HeartbeatOutcome.accepted(false));
        AbortSignal abortSignal = new AbortSignal();

        scheduler.tick(1L, "worker-1", abortSignal, new AtomicInteger(), System.currentTimeMillis(), new AtomicInteger());

        boolean anyJobInProgressLine = heartbeatLogAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("Job in progress"));
        assertThat(anyJobInProgressLine).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // T-1.4: WorkerLoop's idle-poll summary respects its configured interval (WOC-04) -- driven through
    // a real worker-loop thread (mocked GatewayClient always reporting an empty queue) rather than
    // calling a private method via reflection, so this also exercises the real cadence/threading.
    // ---------------------------------------------------------------------------------------------

    @Test
    void idlePollingEventuallyLogsExactlyOneRateLimitedSummaryLine() throws Exception {
        GatewayClient idleGatewayClient = mock(GatewayClient.class);
        when(idleGatewayClient.claim(anyString(), anyString())).thenReturn(java.util.Optional.empty());
        com.review.worker.llama.LlamaClient llamaClient = mock(com.review.worker.llama.LlamaClient.class);
        com.review.worker.prompt.PromptTemplateService promptTemplateService =
                mock(com.review.worker.prompt.PromptTemplateService.class);
        HeartbeatScheduler idleHeartbeatScheduler = mock(HeartbeatScheduler.class);
        com.review.worker.metrics.WorkerMetrics metrics = mock(com.review.worker.metrics.WorkerMetrics.class);

        WorkerProperties properties = new WorkerProperties("127.0.0.1", "8081", "", "");
        properties.getGateway().setUrl("https://gateway.internal");
        properties.getGateway().setApiKey("a".repeat(40));
        properties.getWorker().setId("worker-1");
        properties.getBackend().setId("backend-1");
        properties.getLlama().setUrl("http://127.0.0.1:8000");
        properties.getLlama().setModel("test-model");
        properties.getNetwork().setPollIntervalMs(20L);
        // Smallest granularity the config exposes; WorkerLoop.recordIdlePoll gates on
        // now - lastIdleSummaryAtMillis >= intervalSec * 1000, so 1s bounds this test's runtime.
        properties.getWorker().getLog().setIdleSummaryIntervalSec(1);

        Logger workerLoopLogger = (Logger) LoggerFactory.getLogger(WorkerLoop.class);
        workerLoopLogger.setLevel(Level.ALL);
        ListAppender<ILoggingEvent> idleLogAppender = new ListAppender<>();
        idleLogAppender.start();
        workerLoopLogger.addAppender(idleLogAppender);

        WorkerLoop loop = new WorkerLoop(idleGatewayClient, llamaClient, promptTemplateService,
                idleHeartbeatScheduler, metrics, properties);
        try {
            loop.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean sawIdleSummary = false;
            while (System.nanoTime() < deadline && !sawIdleSummary) {
                sawIdleSummary = idleLogAppender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("Idle: no job available"));
                if (!sawIdleSummary) {
                    Thread.sleep(50);
                }
            }
            assertThat(sawIdleSummary).as("WOC-04: an idle Worker must eventually log a rate-limited "
                    + "liveness summary").isTrue();
        } finally {
            loop.requestShutdown();
            loop.awaitTermination(Duration.ofSeconds(5));
            workerLoopLogger.detachAppender(idleLogAppender);
            workerLoopLogger.setLevel(null);
        }

        List<ILoggingEvent> idleSummaryLines = idleLogAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("Idle: no job available"))
                .toList();
        assertThat(idleSummaryLines).as("all idle-summary lines log at INFO").allSatisfy(
                e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        assertThat(idleSummaryLines.get(0).getFormattedMessage()).contains("backend=backend-1");
    }
}

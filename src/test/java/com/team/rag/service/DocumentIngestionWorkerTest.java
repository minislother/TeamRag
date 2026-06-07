package com.team.rag.service;

import com.team.rag.bean.IngestionJob;
import com.team.rag.bean.IngestionStatus;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.InMemoryDocumentLifecycleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("文档入库 Worker 单元测试")
@ExtendWith(MockitoExtension.class)
class DocumentIngestionWorkerTest {

    @Mock
    private DocumentIngestionService ingestionService;

    private InMemoryDocumentLifecycleRepository repository;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentLifecycleRepository();
        properties = new RagProperties();
    }

    @Test
    @DisplayName("提交任务 - 同一 JVM 内重复提交时只执行一次")
    void testSubmit_DuplicateInProcessJob_RunOnce() {
        // Given
        List<Runnable> tasks = new ArrayList<>();
        DocumentIngestionWorker worker = newWorker(tasks::add);

        // When
        worker.submit("job-1");
        worker.submit("job-1");
        tasks.get(0).run();

        // Then
        assertThat(tasks).hasSize(1);
        verify(ingestionService).ingest("job-1");
    }

    @Test
    @DisplayName("恢复任务 - 扫描 PENDING 和 RUNNING 任务并提交执行")
    void testRecoverPendingJobs_SubmitPendingAndRunningJobs() {
        // Given
        OffsetDateTime now = OffsetDateTime.now();
        repository.saveJob(job("job-pending", IngestionStatus.PENDING, now.minusMinutes(3)));
        repository.saveJob(job("job-running", IngestionStatus.RUNNING, now.minusMinutes(2)));
        repository.saveJob(job("job-success", IngestionStatus.SUCCESS, now.minusMinutes(1)));
        repository.saveJob(job("job-failed", IngestionStatus.FAILED, now));
        DocumentIngestionWorker worker = newWorker(Runnable::run);

        // When
        worker.recoverPendingJobs();

        // Then
        verify(ingestionService).ingest("job-pending");
        verify(ingestionService).ingest("job-running");
        verify(ingestionService, never()).ingest("job-success");
        verify(ingestionService, never()).ingest("job-failed");
    }

    @Test
    @DisplayName("恢复任务 - 关闭恢复扫描时不提交任务")
    void testRecoverPendingJobs_Disabled_Skip() {
        // Given
        properties.getIngestion().setRecoveryEnabled(false);
        repository.saveJob(job("job-pending", IngestionStatus.PENDING, OffsetDateTime.now()));
        DocumentIngestionWorker worker = newWorker(Runnable::run);

        // When
        worker.recoverPendingJobs();

        // Then
        verifyNoInteractions(ingestionService);
    }

    private DocumentIngestionWorker newWorker(TaskExecutor taskExecutor) {
        return new DocumentIngestionWorker(repository, ingestionService, taskExecutor, properties);
    }

    private IngestionJob job(String jobId, IngestionStatus status, OffsetDateTime time) {
        return IngestionJob.builder()
                .id(jobId)
                .documentId("doc-1")
                .version(1)
                .status(status)
                .progress(status == IngestionStatus.RUNNING ? 20 : 0)
                .retryCount(0)
                .createdAt(time)
                .updatedAt(time)
                .build();
    }
}

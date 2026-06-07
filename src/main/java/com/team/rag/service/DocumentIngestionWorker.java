package com.team.rag.service;

import com.team.rag.bean.IngestionJob;
import com.team.rag.config.RagProperties;
import com.team.rag.repository.DocumentLifecycleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent ingestion worker that submits new jobs and recovers unfinished jobs after restart.
 */
@Slf4j
@Service
public class DocumentIngestionWorker {

    private final DocumentLifecycleRepository documentRepository;
    private final DocumentIngestionService ingestionService;
    private final TaskExecutor taskExecutor;
    private final RagProperties properties;
    private final Set<String> runningJobIds = ConcurrentHashMap.newKeySet();

    public DocumentIngestionWorker(DocumentLifecycleRepository documentRepository,
                                   DocumentIngestionService ingestionService,
                                   @Qualifier("documentIngestionTaskExecutor") TaskExecutor taskExecutor,
                                   RagProperties properties) {
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
    }

    /**
     * Submit one ingestion job to the worker pool; duplicate in-process submissions are ignored.
     */
    public void submit(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return;
        }
        if (!runningJobIds.add(jobId)) {
            log.debug("Skip duplicate ingestion job submission: {}", jobId);
            return;
        }
        try {
            taskExecutor.execute(() -> run(jobId));
        } catch (RuntimeException ex) {
            runningJobIds.remove(jobId);
            throw ex;
        }
    }

    /**
     * Periodically scans persistent jobs so PENDING/RUNNING jobs are not lost after a service restart.
     */
    @Scheduled(
            fixedDelayString = "${rag.ingestion.recovery-fixed-delay-ms:30000}",
            initialDelayString = "${rag.ingestion.recovery-initial-delay-ms:5000}"
    )
    public void recoverPendingJobs() {
        if (!Boolean.TRUE.equals(properties.getIngestion().getRecoveryEnabled())) {
            return;
        }
        int batchSize = Math.max(1, properties.getIngestion().getRecoveryBatchSize());
        List<IngestionJob> jobs = documentRepository.findRecoverableJobs(batchSize);
        for (IngestionJob job : jobs) {
            submit(job.getId());
        }
    }

    private void run(String jobId) {
        try {
            ingestionService.ingest(jobId);
        } finally {
            runningJobIds.remove(jobId);
        }
    }
}

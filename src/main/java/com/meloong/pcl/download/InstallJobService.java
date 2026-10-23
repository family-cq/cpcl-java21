package com.meloong.pcl.download;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * 后台安装任务，供界面轮询进度。
 *
 * <p>下载动辄几分钟，接口不能同步阻塞，因此统一返回 jobId，由前端轮询
 * {@code GET /api/install/jobs/{id}}。
 */
@Service
public class InstallJobService {

    private static final Logger log = LoggerFactory.getLogger(InstallJobService.class);
    private static final int MAX_LOGS = 120;

    /** 任务向调度器汇报进度。 */
    public interface Progress {
        void update(int percent, String message);

        /**
         * 进入第 {@code index} 个阶段（从 1 开始）。
         *
         * <p>整合包这类多阶段安装会在界面上渲染成步骤条；普通任务不调用即可，
         * 默认空实现，所以不影响既有 lambda 写法。
         */
        default void stage(int index, int total, String name) {
        }
    }

    /** 对外快照。 */
    public record JobView(String id, String kind, String title, String state, int progress,
                          String message, long startedAt, long finishedAt,
                          List<String> logs, Map<String, Object> result,
                          List<String> stages, int stageIndex, String stageName,
                          long speedBps, String speed) {
    }

    private static final class Job {
        String id;
        String kind;
        String title;
        List<String> stages = List.of();
        volatile String state = "running";
        volatile int progress = 0;
        volatile String message = "准备中…";
        volatile int stageIndex = 0;
        volatile String stageName = "";
        long startedAt = System.currentTimeMillis();
        volatile long finishedAt = 0;
        final Deque<String> logs = new ArrayDeque<>();
        volatile Map<String, Object> result;
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final DownloadManager downloadManager;
    private final ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "pcl-install-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    public InstallJobService(DownloadManager downloadManager) {
        this.downloadManager = downloadManager;
    }

    /** 提交任务，立即返回 jobId。 */
    public JobView submit(String kind, String title, Function<Progress, Map<String, Object>> work) {
        return submit(kind, title, List.of(), work);
    }

    /**
     * 提交带阶段清单的任务（界面上渲染步骤条）。
     *
     * @param stages 阶段名，按执行顺序排列；为空则不显示步骤条
     */
    public JobView submit(String kind, String title, List<String> stages,
                          Function<Progress, Map<String, Object>> work) {
        Job job = new Job();
        job.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        job.kind = kind;
        job.title = title;
        job.stages = stages == null ? List.of() : List.copyOf(stages);
        jobs.put(job.id, job);

        Progress progress = new Progress() {
            @Override
            public void update(int percent, String message) {
                job.progress = Math.max(0, Math.min(100, percent));
                job.message = message;
                appendLog(job, message);
            }

            @Override
            public void stage(int index, int total, String name) {
                job.stageIndex = index;
                job.stageName = name;
                if (name != null && !name.isBlank()) {
                    appendLog(job, "步骤 " + index + "/" + total + " · " + name);
                }
            }
        };
        logLine(job, "开始：" + title);

        pool.submit(() -> {
            try {
                Map<String, Object> result = work.apply(progress);
                job.result = result;
                job.progress = 100;
                job.state = "done";
                job.message = "已完成";
                logLine(job, "完成：" + title);
            } catch (Throwable ex) {
                job.state = "failed";
                job.message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                logLine(job, "失败：" + job.message);
                log.error("安装任务失败：{}", title, ex);
            } finally {
                job.finishedAt = System.currentTimeMillis();
            }
        });
        return view(job);
    }

    public JobView get(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            throw new IllegalArgumentException("任务不存在：" + id);
        }
        return view(job);
    }

    /** 最近的 20 个任务。 */
    public List<JobView> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong((Job job) -> job.startedAt).reversed())
                .limit(20)
                .map(this::view)
                .toList();
    }

    private JobView view(Job job) {
        List<String> logs;
        synchronized (job.logs) {
            logs = new ArrayList<>(job.logs);
        }
        long speedBps = downloadManager.currentSpeedBps();
        return new JobView(job.id, job.kind, job.title, job.state, job.progress, job.message,
                job.startedAt, job.finishedAt, logs, job.result,
                job.stages, job.stageIndex, job.stageName,
                speedBps, DownloadManager.formatSpeed(speedBps));
    }

    private void appendLog(Job job, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        synchronized (job.logs) {
            if (!job.logs.isEmpty() && message.equals(job.logs.peekLast())) {
                return;
            }
            job.logs.addLast(message);
            while (job.logs.size() > MAX_LOGS) {
                job.logs.removeFirst();
            }
        }
    }

    private void logLine(Job job, String message) {
        appendLog(job, message);
        log.info("[{}] {}", job.title, message);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}

package tn.mnlr.vripper.download;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tn.mnlr.vripper.host.Host;
import tn.mnlr.vripper.jpa.domain.Image;
import tn.mnlr.vripper.jpa.domain.Post;
import tn.mnlr.vripper.jpa.domain.enums.Status;
import tn.mnlr.vripper.services.DataService;
import tn.mnlr.vripper.services.PostService;
import tn.mnlr.vripper.services.SettingsService;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class DownloadService {

  private static final List<Status> FINISHED =
      Arrays.asList(Status.ERROR, Status.COMPLETE, Status.STOPPED);
  private final int MAX_POOL_SIZE = 12;

  private final ConcurrentHashMap<Host, AtomicInteger> threadCount = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newFixedThreadPool(MAX_POOL_SIZE);
  private final BlockingQueue<DownloadJob> executionQueue = new LinkedBlockingQueue<>();
  private final List<DownloadJob> executing = Collections.synchronizedList(new ArrayList<>());

  private final PendingQueue pendingQueue;
  private final SettingsService settings;
  private final DataService dataService;
  private final PostService postService;

  private boolean pauseQ = false;
  private Thread executionThread;
  private Thread pollThread;

  @Autowired
  public DownloadService(
      PendingQueue pendingQueue,
      SettingsService settings,
      DataService dataService,
      PostService postService) {
    this.pendingQueue = pendingQueue;
    this.settings = settings;
    this.dataService = dataService;
    this.postService = postService;
  }

  @PostConstruct
  private void init() {
    executionThread = new Thread(this::start, "Executor thread");
    pollThread = new Thread(this::poll, "Polling thread");
    pollThread.start();
    executionThread.start();
  }

  public void destroy() throws Exception {
    log.info("Shutting down ExecutionService");
    executionThread.interrupt();
    pollThread.interrupt();
    executor.shutdown();
    dataService
        .findAllPosts()
        .forEach(
            p -> {
              log.debug(String.format("Stopping download jobs for %s", p));
              this.stopRunning(p.getPostId());
            });
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  private void stopRunning(@NonNull String postId) {
    List<DownloadJob> stopping = new ArrayList<>();
    Iterator<DownloadJob> iterator = executing.iterator();
    while (iterator.hasNext()) {
      DownloadJob downloadJob = iterator.next();
      if (postId.equals(downloadJob.getPost().getPostId())) {
        downloadJob.stop();
        iterator.remove();
        stopping.add(downloadJob);
      }
    }

    while (!stopping.isEmpty()) {
      stopping.removeIf(DownloadJob::isFinished);
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void stopAll(List<String> posIds) {
    if (posIds != null) {
      posIds.forEach(this::stop);
    } else {
      dataService.findAllPosts().forEach(p -> this.stop(p.getPostId()));
    }
  }

  public void restartAll(List<String> posIds) {
    if (posIds != null) {
      posIds.forEach(this::restart);
    } else {
      dataService.findAllPosts().forEach(p -> this.restart(p.getPostId()));
    }
  }

  private void restart(@NonNull String postId) {
    if (isPending(postId)) {
      log.warn(String.format("Cannot restart, jobs are currently running for post id %s", postId));
      return;
    }
    List<Image> images = dataService.findByPostIdAndIsNotCompleted(postId);
    if (images.isEmpty()) {
      return;
    }
    Post post = dataService.findPostByPostId(postId).orElseThrow();
    post.setStatus(Status.PENDING);
    dataService.updatePostStatus(post.getStatus(), post.getId());
    log.debug(String.format("Restarting %d jobs for post id %s", images.size(), postId));
    for (Image image : images) {
      try {
        pendingQueue.put(post, image);
      } catch (InterruptedException e) {
        log.warn("Thread was interrupted", e);
        Thread.currentThread().interrupt();
      }
    }
  }

  private boolean isPending(String postId) {
    return pendingQueue.isPending(postId);
  }

  private void stop(String postId) {
    try {
      pauseQ = true;
      final Post post = dataService.findPostByPostId(postId).orElseThrow();
      if (post == null) {
        return;
      }
      if (FINISHED.contains(post.getStatus())) {
        return;
      }
      pendingQueue.stop(post);
      stopRunning(postId);
      dataService.stopImagesByPostIdAndIsNotCompleted(postId);
      dataService.finishPost(post);
      postService.stopFetchingMetadata(post);
    } finally {
      pauseQ = false;
    }
  }

  private boolean canRun(Host host) {
    boolean canRun;
    AtomicInteger count = threadCount.get(host);
    if (count == null) {
      threadCount.put(host, new AtomicInteger(0));
    }
    canRun =
        threadCount.get(host).get() < settings.getSettings().getMaxThreads()
            && (settings.getSettings().getMaxTotalThreads() == 0
                ? threadCount.values().stream().mapToInt(AtomicInteger::get).sum() < MAX_POOL_SIZE
                : threadCount.values().stream().mapToInt(AtomicInteger::get).sum()
                    < settings.getSettings().getMaxTotalThreads());
    if (canRun && !pauseQ) {
      threadCount.get(host).incrementAndGet();
      return true;
    }
    return false;
  }

  private void poll() {
    while (!Thread.interrupted()) {
      try {
        List<DownloadJob> peek = pendingQueue.peek();
        for (DownloadJob downloadJob : peek) {
          if (canRun(downloadJob.getImage().getHost())) {
            executionQueue.offer(downloadJob);
            pendingQueue.remove(downloadJob);
          }
        }
        synchronized (threadCount) {
          threadCount.wait(2_000);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void start() {
    while (!Thread.interrupted()) {
      try {
        push(executionQueue.take());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.error("Execution Service failed", e);
        break;
      }
    }
  }

  private void push(DownloadJob downloadJob) {
    log.debug(String.format("Scheduling a job for %s", downloadJob.getImage().getUrl()));
    executor.execute(new DownloadRunnable(downloadJob));
    executing.add(downloadJob);
  }

  public synchronized void afterJobFinish(DownloadJob downloadJob) {
    int count = pendingQueue.decrement(downloadJob.getPost().getPostId());
    if (count == 0) {
      dataService.finishPost(downloadJob.getPost());
    }
    threadCount.get(downloadJob.getImage().getHost()).decrementAndGet();
    executing.remove(downloadJob);
    synchronized (threadCount) {
      threadCount.notify();
    }
  }

  public int runningCount() {
    return executing.size();
  }
}

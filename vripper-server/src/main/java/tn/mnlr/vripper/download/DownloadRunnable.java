package tn.mnlr.vripper.download;

import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import tn.mnlr.vripper.SpringContext;
import tn.mnlr.vripper.VripperApplication;
import tn.mnlr.vripper.jpa.domain.enums.Status;
import tn.mnlr.vripper.services.DataService;

@Slf4j
public class DownloadRunnable implements Runnable {

    private final DownloadService downloadService;
    private final DataService dataService;

    private final DownloadJob downloadJob;

    public DownloadRunnable(final DownloadJob downloadJob) {
        downloadService = SpringContext.getBean(DownloadService.class);
        dataService = SpringContext.getBean(DataService.class);
        this.downloadJob = downloadJob;
    }

    @Override
    public void run() {
        Failsafe.with(VripperApplication.retryPolicy)
                .onFailure(e -> {
                    log.error(String.format("Failed to download %s after %d tries", downloadJob.getImage().getUrl(), e.getAttemptCount()), e.getFailure());
                    downloadJob.getImage().setStatus(Status.ERROR);
                    dataService.updateImageStatus(downloadJob.getImage().getStatus(), downloadJob.getImage().getId());
                })
                .onComplete(e -> {
                    dataService.afterJobFinish(downloadJob.getImage(), downloadJob.getPost());
                    downloadService.afterJobFinish(downloadJob);
                    log.debug(String.format("Finished downloading %s", downloadJob.getImage().getUrl()));
                }).run(downloadJob);
    }
}

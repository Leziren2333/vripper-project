package tn.mnlr.vripper.download;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.function.CheckedRunnable;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.AbstractExecutionAwareRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.util.EntityUtils;
import tn.mnlr.vripper.SpringContext;
import tn.mnlr.vripper.exception.DownloadException;
import tn.mnlr.vripper.exception.HostException;
import tn.mnlr.vripper.jpa.domain.Image;
import tn.mnlr.vripper.jpa.domain.Post;
import tn.mnlr.vripper.jpa.domain.enums.Status;
import tn.mnlr.vripper.services.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class DownloadJob implements CheckedRunnable {

    public enum ContextAttributes {
        OPEN_CONNECTION("OPEN_CONNECTION");

        private final String value;

        ContextAttributes(String value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final int READ_BUFFER_SIZE = 8192;

    private final DataService dataService;
    private final PathService pathService;
    private final ConnectionService cm;
    private final VGAuthService authService;
    private final DownloadSpeedService downloadSpeedService;
    private final SettingsService settingsService;

    private final HttpClientContext context;

    @Getter
    private final Image image;

    @Getter
    private final Post post;

    private boolean stopped = false;

    @Getter
    private boolean finished = false;

    DownloadJob(Post post, Image image) {
        this.image = image;
        this.post = post;
        dataService = SpringContext.getBean(DataService.class);
        pathService = SpringContext.getBean(PathService.class);
        cm = SpringContext.getBean(ConnectionService.class);
        authService = SpringContext.getBean(VGAuthService.class);
        downloadSpeedService = SpringContext.getBean(DownloadSpeedService.class);
        settingsService = SpringContext.getBean(SettingsService.class);
        context = HttpClientContext.create();
        context.setCookieStore(new BasicCookieStore());
        context.setAttribute(ContextAttributes.OPEN_CONNECTION.toString(), Collections.synchronizedList(new ArrayList<AbstractExecutionAwareRequest>()));
    }

    public void download(final Post post, final Image image) throws DownloadException {

        try {

            image.setStatus(Status.DOWNLOADING);
            image.setCurrent(0);
            dataService.updateImageStatus(image.getStatus(), image.getId());
            dataService.updateImageCurrent(image.getCurrent(), image.getId());

            try {
                LOCK.lock();
                if (!post.getStatus().equals(Status.DOWNLOADING) && !post.getStatus().equals(Status.PARTIAL)) {
                    post.setStatus(Status.DOWNLOADING);
                    dataService.updatePostStatus(post.getStatus(), post.getId());
                }
            } finally {
                LOCK.unlock();
            }


            if (stopped) {
                return;
            }
            /*
             * HOST SPECIFIC
             */
            log.debug(String.format("Getting image url and name from %s using %s", image.getUrl(), image.getHost()));
            HostService.NameUrl nameAndUrl = image.getHost().getNameAndUrl(image.getUrl(), context);
            log.debug(String.format("Resolved name for %s: %s", image.getUrl(), nameAndUrl.getName()));
            log.debug(String.format("Resolved image url for %s: %s", image.getUrl(), nameAndUrl.getUrl()));
            /*
             * END HOST SPECIFIC
             */

            if (stopped) {
                return;
            }
            String formatImageFileName = pathService.formatImageFileName(nameAndUrl.getName());
            log.debug(String.format("Sanitizing image name from %s to %s", nameAndUrl.getName(), formatImageFileName));
            nameAndUrl = new HostService.NameUrl(formatImageFileName, nameAndUrl.getUrl());

            HttpClient client = cm.getClient().build();

            log.debug(String.format("Downloading %s", nameAndUrl.getUrl()));
            HttpGet httpGet = cm.buildHttpGet(nameAndUrl.getUrl(), context);
            httpGet.addHeader("Referer", image.getUrl());
            try (CloseableHttpResponse response = (CloseableHttpResponse) client.execute(httpGet, context)) {

                if (response.getStatusLine().getStatusCode() / 100 != 2) {
                    EntityUtils.consumeQuietly(response.getEntity());
                    throw new DownloadException(String.format("Server returned code %d", response.getStatusLine().getStatusCode()));
                }
                if (stopped) {
                    return;
                }
                File destinationFolder, outputFile;
                try {
                    LOCK.lock();
                    Post updatedPost = dataService.findPostById(post.getId()).orElseThrow();
                    if (updatedPost.getPostFolderName() == null) {
                        pathService.createDefaultPostFolder(updatedPost);
                    }
                    destinationFolder = pathService.getDownloadDestinationFolder(updatedPost);
                    if (settingsService.getSettings().getLeaveThanksOnStart()) {
                        authService.leaveThanks(updatedPost);
                    }
                    outputFile = new File(destinationFolder.getPath() + File.separator + String.format("%03d_", image.getIndex()) + nameAndUrl.getName() + ".tmp");
                    outputFile.getParentFile().mkdirs();
                } finally {
                    LOCK.unlock();
                }
                try (InputStream downloadStream = response.getEntity().getContent(); FileOutputStream fos = new FileOutputStream(outputFile)) {

                    if (stopped) {
                        return;
                    }

                    image.setTotal(response.getEntity().getContentLength());
                    dataService.updateImageTotal(image.getTotal(), image.getId());

                    log.debug(String.format("%s length is %d", nameAndUrl.getUrl(), image.getTotal()));
                    log.debug(String.format("Starting data transfer for %s", nameAndUrl.getUrl()));

                    byte[] buffer = new byte[READ_BUFFER_SIZE];
                    int read;
                    while ((read = downloadStream.read(buffer, 0, READ_BUFFER_SIZE)) != -1 && !stopped) {
                        fos.write(buffer, 0, read);
                        image.increase(read);
                        downloadSpeedService.increase(read);
                        dataService.updateImageCurrent(image.getCurrent(), image.getId());
                    }
                    fos.flush();
                    EntityUtils.consumeQuietly(response.getEntity());
                    if (stopped) {
                        return;
                    }
                }
                checkImageTypeAndRename(dataService.findPostById(post.getId()).orElseThrow(), outputFile, nameAndUrl.getName(), image.getIndex());
            }
        } catch (Exception e) {
            if (stopped) {
                return;
            }
            throw new DownloadException(e);
        } finally {
            if (image.getCurrent() == image.getTotal() && image.getTotal() > 0) {
                image.setStatus(Status.COMPLETE);
            } else if (stopped) {
                image.setStatus(Status.STOPPED);
            } else {
                image.setStatus(Status.ERROR);
            }
            dataService.updateImageStatus(image.getStatus(), image.getId());
            finished = true;
        }
    }

    private void checkImageTypeAndRename(Post post, File outputFile, String imageName, int index) throws HostException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(outputFile)) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                throw new HostException("Image file is not recognized!");
            }
            ImageReader reader = it.next();
            if (reader.getFormatName().toUpperCase().equals("JPEG")) {
                String imageNameLC = imageName.toLowerCase();
                if (!imageNameLC.endsWith("_jpg") && !imageNameLC.endsWith("_jpeg")) {
                    imageName += ".jpg";
                } else {
                    String toReplace = null;
                    if (imageNameLC.endsWith("_jpg")) {
                        toReplace = "_jpg";
                    } else if (imageNameLC.endsWith("_jpeg")) {
                        toReplace = "_jpeg";
                    }
                    if (toReplace != null) {
                        imageName = imageName.substring(0, imageName.length() - toReplace.length()) + ".jpg";
                    }
                }
            } else if (reader.getFormatName().toUpperCase().equals("PNG")) {
                String imageNameLC = imageName.toLowerCase();
                if (!imageNameLC.endsWith("_png")) {
                    imageName += ".png";
                } else {
                    String toReplace = null;
                    if (imageNameLC.endsWith("_png")) {
                        toReplace = "_png";
                    }
                    if (toReplace != null) {
                        imageName = imageName.substring(0, imageName.length() - toReplace.length()) + ".png";
                    }
                }
            }
        } catch (Exception e) {
            throw new HostException("Failed to guess image format", e);
        }
        try {
            File downloadDestinationFolder = pathService.getDownloadDestinationFolder(post);
            File outImage = new File(downloadDestinationFolder, (settingsService.getSettings().getForceOrder() ? String.format("%03d_", index) : "") + imageName);
            if (outImage.exists() && outImage.delete()) {
                log.debug(String.format("%s is deleted", outImage.toString()));
            }
            Files.move(outputFile.toPath(), outImage.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            throw new HostException("Failed to rename the image", e);
        }
    }

    @Override
    public void run() throws Exception {
        if (stopped) {
            finished = true;
            return;
        }
        log.debug(String.format("Starting downloading %s", image.getUrl()));
        download(post, image);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DownloadJob that = (DownloadJob) o;
        return Objects.equals(image, that.image) &&
                Objects.equals(post, that.post);
    }

    @Override
    public int hashCode() {
        return Objects.hash(image, post);
    }

    public void stop() {
        List<AbstractExecutionAwareRequest> requests = (List<AbstractExecutionAwareRequest>) this.context.getAttribute(ContextAttributes.OPEN_CONNECTION.toString());
        if (requests != null) {
            for (AbstractExecutionAwareRequest request : requests) {
                request.abort();
            }
        }
        this.stopped = true;
    }
}

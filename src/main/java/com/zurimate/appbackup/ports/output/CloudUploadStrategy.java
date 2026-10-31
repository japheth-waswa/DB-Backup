package com.zurimate.appbackup.ports.output;

import com.zurimate.appbackup.service.cloud.CloudConfig;
import com.zurimate.appbackup.utils.CloudType;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Off-site storage provider (Backblaze B2 today). Implementations are stateless and open a fresh,
 * closed-after-use client for every call so credentials of different jobs never mix.
 */
public interface CloudUploadStrategy {

    CloudType cloudType();

    /**
     * Uploads one file. Implementations throw {@link com.zurimate.appbackup.exception.CloudUploadException}
     * and say whether retrying could help.
     */
    void uploadFileToCloud(Path fileToUpload, String destinationPath, CloudConfig config);

    /**
     * Deletes remote files under {@code prefix} uploaded before {@code cutoff}.
     *
     * @return number of files removed
     */
    int deleteOlderThan(String prefix, Instant cutoff, CloudConfig config);
}

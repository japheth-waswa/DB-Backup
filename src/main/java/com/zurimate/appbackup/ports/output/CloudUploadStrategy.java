package com.zurimate.appbackup.ports.output;

import com.zurimate.appbackup.service.cloud.CloudConfig;

import java.nio.file.Path;

public interface CloudUploadStrategy {
    void uploadFileToCloud(Path fileToUpload, String destinationPath, CloudConfig config);
}

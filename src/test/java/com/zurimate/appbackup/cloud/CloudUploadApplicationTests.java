package com.zurimate.appbackup.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zurimate.appbackup.config.BackBlazeTestConfig;
import com.zurimate.appbackup.service.cloud.CloudUploadContext;
import com.zurimate.appbackup.utils.CloudType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static com.zurimate.appbackup.utils.Helpers.parseFileNameWithDateTime;

@Slf4j
@SpringBootTest
public class CloudUploadApplicationTests {

    @Autowired
    private CloudUploadContext cloudUploadContext;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String FILE_TO_UPLOAD_PATH = "test-backup-rel/db-2025-09-01-17-14-11.zip";

    //backblaze
    private static final String BACK_BLAZE_KEY_FILE = "test-backblaze-key.json";

//    @Test
    void testBackBlazeUploadConfig() throws IOException {
        String json = Files.readString(Paths.get(BACK_BLAZE_KEY_FILE));

        var configData = objectMapper.readValue(json, BackBlazeTestConfig.class);
        log.info("key ID: {}, App Key: {}",
                configData.keyName(),
                configData.applicationKey());

        cloudUploadContext.uploadFileToCloud(CloudType.BACK_BLAZE,
                Paths.get(FILE_TO_UPLOAD_PATH),
                "test/%s".formatted(parseFileNameWithDateTime()),
                configData.toBackBlazeConfig()
//                BackBlazeConfig.builder()
//                        .keyId(configData.keyId())
//                        .keyName(configData.keyName())
//                        .applicationKey(configData.applicationKey())
//                        .bucketName(configData.bucketName())
//                        .build()
        );
    }

}

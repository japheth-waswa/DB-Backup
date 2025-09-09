package com.zurimate.appbackup.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zurimate.appbackup.config.BackBlazeTestConfig;
import com.zurimate.appbackup.config.TestDBConfigs;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.service.BackupContext;
import com.zurimate.appbackup.utils.CloudType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@SpringBootTest
public class DBOtherBackupApplicationTests {

    @Autowired
    private BackupContext backupContext;

    @Autowired
    private ObjectMapper objectMapper;


    private static final DBConfig mysqlDbConfig = TestDBConfigs.getMysqlConfig();
    private static final DBConfig postgresqlDbConfig = TestDBConfigs.getPostgresqlConfig();
    private String dbDestinationRelative = "test-backup-rel";
    private String mysqlDestinationAbsolute = "/Users/japhethelijah/dev/java/apps/AppBackup/test-backup-abs";
    //    private List<String> otherDirsToBackup = List.of("/Users/japhethelijah/Downloads/AppBackup", "/Users/japhethelijah/Downloads/smis");
    private List<String> otherDirsToBackup = List.of("/Users/japhethelijah/Downloads/dev/zurimate-nginx");

    //backblaze
    private static final String BACK_BLAZE_KEY_FILE = "test-backblaze-key.json";

//    @Test
    void mysqlBackupTest_Relative_Destination() throws InterruptedException, IOException {
//        assertThrows(Exception.class,()->backupContext.backup(mysqlDbConfig, mysqlDestinationRelative, mysqlOtherDirsToBackup));
        //backblaze
        String json = Files.readString(Paths.get(BACK_BLAZE_KEY_FILE));

        var configData = objectMapper.readValue(json, BackBlazeTestConfig.class);

        var res = backupContext.backup(mysqlDbConfig, dbDestinationRelative, otherDirsToBackup,
                CloudType.BACK_BLAZE,
                "test/mysql",
                configData.toBackBlazeConfig()
//                BackBlazeConfig.builder()
//                        .keyId(configData.keyId())
//                        .keyName(configData.keyName())
//                        .applicationKey(configData.applicationKey())
//                        .bucketName(configData.bucketName())
//                        .build()
        );
        log.info("{}", res);
        Thread.sleep(15000);
    }

//    @Test
    void postgresqlBackupTest_Relative_Destination() throws InterruptedException, IOException {
//        var res = backupContext.backup(postgresqlDbConfig, dbDestinationRelative, otherDirsToBackup);
        //backblaze
        String json = Files.readString(Paths.get(BACK_BLAZE_KEY_FILE));

        var configData = objectMapper.readValue(json, BackBlazeTestConfig.class);


        var res = backupContext.backup(postgresqlDbConfig, dbDestinationRelative, otherDirsToBackup,
                CloudType.BACK_BLAZE,
                "test/postgresql",
                configData.toBackBlazeConfig()
//                BackBlazeConfig.builder()
//                        .keyId(configData.keyId())
//                        .keyName(configData.keyName())
//                        .applicationKey(configData.applicationKey())
//                        .bucketName(configData.bucketName())
//                        .build()
        );
        log.info("{}", res);
        Thread.sleep(15000);
    }

//    @Test
//    void mysqlBackupTest_Absolute_Destination() {
//        backupContext.backup(mysqlDbConfig, mysqlDestinationAbsolute, mysqlOtherDirsToBackup);
//        Thread.sleep(15000);
//    }
//
//    @Test
//    void backupRequest() {
//        log.info("{}", new BackupRequest(mysqlDbConfig, mysqlDestinationAbsolute, mysqlOtherDirsToBackup));
//    }

}

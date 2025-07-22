package com.zurimate.appbackup;

import com.zurimate.appbackup.service.BackupContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
class AppBackupApplicationTests {

    @Autowired
    private BackupContext backupContext;

//    private static final DBConfig mysqlDbConfig = TestDBConfigs.getMysqlConfig();
    private String mysqlDestinationRelative = "test-backup-rel";
    private String mysqlDestinationAbsolute = "/Users/japhethelijah/dev/java/apps/AppBackup/test-backup-abs";
    private List<String> mysqlOtherDirsToBackup = List.of("/Users/japhethelijah/Downloads/AppBackup", "/Users/japhethelijah/Downloads/smis");

    @BeforeAll
    static void init() {
    }

    @Test
    void mysqlBackupTest_Relative_Destination() throws InterruptedException {
       var res= backupContext.backup(TestDBConfigs.getMysqlConfig(), mysqlDestinationRelative, mysqlOtherDirsToBackup);
       log.info("{}",res);
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

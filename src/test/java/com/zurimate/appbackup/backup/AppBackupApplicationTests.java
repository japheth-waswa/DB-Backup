package com.zurimate.appbackup.backup;

import com.zurimate.appbackup.config.TestDBConfigs;
import com.zurimate.appbackup.dto.DBConfig;
import com.zurimate.appbackup.service.BackupContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
public class AppBackupApplicationTests {

    @Autowired
    private BackupContext backupContext;

    private static final DBConfig mysqlDbConfig = TestDBConfigs.getMysqlConfig();
    private String mysqlDestinationRelative = "test-backup-rel";
    private String mysqlDestinationAbsolute = "/Users/japhethelijah/dev/java/apps/AppBackup/test-backup-abs";
    private List<String> mysqlOtherDirsToBackup = List.of("/Users/japhethelijah/Downloads/AppBackup", "/Users/japhethelijah/Downloads/smis");

    @BeforeAll
    static void init() {
    }

//    @Test
    void mysqlBackupTest_Relative_Destination() throws InterruptedException {
//        assertThrows(Exception.class,()->backupContext.backup(mysqlDbConfig, mysqlDestinationRelative, mysqlOtherDirsToBackup));
       var res= backupContext.backup(mysqlDbConfig, mysqlDestinationRelative, mysqlOtherDirsToBackup);
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

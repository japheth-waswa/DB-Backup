package com.zurimate.AppBackup.adapter.input;

import com.zurimate.AppBackup.dto.BackupDestination;
import com.zurimate.AppBackup.dto.BackupRequest;
import com.zurimate.AppBackup.service.BackupContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
class BackupController {
    private final BackupContext backupContext;

    @PostMapping("backup")
    ResponseEntity<BackupDestination> backup(@RequestBody BackupRequest backupRequest) {
        log.info("Received backup request: {}", backupRequest);
        return ResponseEntity.ok(backupContext.backup(backupRequest.dbConfig(),
                backupRequest.destination(),
                backupRequest.otherDirsToBackup()));
    }
}

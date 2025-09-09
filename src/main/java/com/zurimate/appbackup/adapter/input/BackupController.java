package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.dto.ApiResponse;
import com.zurimate.appbackup.dto.BackupDestination;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
class BackupController {
    private final BackupSchedulerService backupSchedulerService;

    @GetMapping("init")
    ResponseEntity<ApiResponse<BackupDestination>> initiateAllBackups() {
        log.info("Initiate all backups");
        backupSchedulerService.initiateAllBackups();
        return ResponseEntity.ok(new ApiResponse<>(null, null));
    }

    @PostMapping("backup")
    ResponseEntity<ApiResponse<BackupDestination>> backup(@RequestBody BackupRequest backupRequest) {
        log.info("Received backup request: {}", backupRequest);
        return ResponseEntity.ok(new ApiResponse<>(null, backupSchedulerService.
                createScheduledBackup(backupRequest)));
    }
}

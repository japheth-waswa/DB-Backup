package com.zurimate.appbackup.adapter.input;

import com.zurimate.appbackup.dto.ApiResponse;
import com.zurimate.appbackup.dto.BackupRequest;
import com.zurimate.appbackup.dto.BackupRunView;
import com.zurimate.appbackup.dto.BackupSummary;
import com.zurimate.appbackup.ports.input.BackupSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API. Every endpoint except {@code /actuator/health} requires the {@code X-API-Key} header.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
class BackupController {
    private final BackupSchedulerService backupSchedulerService;

    /** Kept for existing scripts; prefer POST /backups/{id}/run. */
    @GetMapping("init")
    ResponseEntity<ApiResponse<Void>> initiateAllBackups() {
        log.info("Initiate all backups");
        backupSchedulerService.initiateAllBackups();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>("All backups queued", null));
    }

    @PostMapping("backup")
    ResponseEntity<ApiResponse<BackupSummary>> backup(@Valid @RequestBody BackupRequest backupRequest) {
        log.info("Received backup request: {}", backupRequest);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>("Backup registered and queued", backupSchedulerService.createScheduledBackup(backupRequest)));
    }

    @GetMapping("backups")
    ApiResponse<List<BackupSummary>> listBackups() {
        return new ApiResponse<>(null, backupSchedulerService.listBackups());
    }

    @GetMapping("backups/{id}")
    ApiResponse<BackupSummary> getBackup(@PathVariable UUID id) {
        return new ApiResponse<>(null, backupSchedulerService.getBackup(id));
    }

    @GetMapping("backups/{id}/runs")
    ApiResponse<List<BackupRunView>> listRuns(@PathVariable UUID id, @RequestParam(defaultValue = "20") int limit) {
        return new ApiResponse<>(null, backupSchedulerService.listRuns(id, limit));
    }

    @PostMapping("backups/{id}/run")
    ResponseEntity<ApiResponse<Void>> runBackup(@PathVariable UUID id) {
        backupSchedulerService.triggerBackup(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ApiResponse<>("Backup queued", null));
    }

    @DeleteMapping("backups/{id}")
    ApiResponse<Void> deleteBackup(@PathVariable UUID id) {
        backupSchedulerService.deleteBackup(id);
        return new ApiResponse<>("Backup deleted; existing archives were kept", null);
    }
}

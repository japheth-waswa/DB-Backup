package com.zurimate.AppBackup.dto;

import java.util.List;

public record BackupRequest(DBConfig dbConfig, String destination, List<String> otherDirsToBackup) {
}

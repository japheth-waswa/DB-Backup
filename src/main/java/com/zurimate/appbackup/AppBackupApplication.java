package com.zurimate.appbackup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of AppBackup: a scheduler that takes logical backups of SQL and NoSQL databases,
 * archives them with checksums, optionally ships them to cloud storage and keeps an audit trail of every run.
 */
@SpringBootApplication
@EnableScheduling
public class AppBackupApplication {
	public static void main(String[] args) {
		SpringApplication.run(AppBackupApplication.class, args);
	}
}

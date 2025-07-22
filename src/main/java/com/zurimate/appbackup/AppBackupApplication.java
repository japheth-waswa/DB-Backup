package com.zurimate.appbackup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AppBackupApplication {
	public static void main(String[] args) {
		SpringApplication.run(AppBackupApplication.class, args);
	}
}

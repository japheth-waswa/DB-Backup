package com.zurimate.appbackup.utils;

/**
 * How often a job runs. Each value maps to one cron trigger in {@code Scheduler}.
 */
public enum SchedulerType {
    MINUTE,//TODO REMOVE THIS AFTER TESTING
    HOURLY,
    EVERY_6_HOURS,
    EVERY_12_HOURS,
    DAILY,
    EVERY_3_DAYS,
    EVERY_WEEK,
    EVERY_FORTNIGHT,
    MONTHLY,
    ANNUALLY
}

package com.zurimate.appbackup.utils;

/**
 * Outcome of a single backup run as stored in {@code backup_runs}.
 */
public enum BackupRunStatus {
    RUNNING,
    /** Every table was dumped and archived. */
    SUCCESS,
    /** Archive written but some tables could not be dumped. Check the run message. */
    PARTIAL,
    FAILED,
    /** Another run of the same job was still in progress. */
    SKIPPED
}

# Runtime image for the native AppBackup executable (build it with docker/Dockerfile.native-build).
# distroless/base ships glibc and CA certificates and nothing else: no shell, no package manager.
FROM gcr.io/distroless/base

WORKDIR /app

# --chmod sets the executable bit at copy time; distroless has no shell to run chmod
COPY --chmod=755 AppBackup /app/AppBackup

# Runs as root because existing backup volumes on the hosts are root owned.
# Switch to USER nonroot:nonroot once /backups and /app/logs are chowned to uid 65532.

ENTRYPOINT ["/app/AppBackup"]

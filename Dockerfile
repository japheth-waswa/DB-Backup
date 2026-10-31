# Minimal, secure runtime
FROM gcr.io/distroless/base

WORKDIR /app

# Copy native binary
COPY AppBackup .

# Ensure executable (important)
# (won't fail if already executable)
USER root
RUN chmod +x /app/AppBackup

# Drop privileges (optional but good practice)
USER nonroot:nonroot

ENTRYPOINT ["/app/AppBackup"]
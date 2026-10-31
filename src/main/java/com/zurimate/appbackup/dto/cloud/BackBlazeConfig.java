package com.zurimate.appbackup.dto.cloud;

import com.zurimate.appbackup.service.cloud.CloudConfig;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Backblaze B2 credentials and target bucket. The application key is never printed.
 */
@Builder
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BackBlazeConfig implements CloudConfig {

    @NotBlank(message = "Back Blaze Key ID is required")
    private String keyId;

    @NotBlank(message = "Back Blaze Key Name is required")
    private String keyName;

    @ToString.Exclude
    @NotBlank(message = "Back Blaze Application Key is required")
    private String applicationKey;

    @NotBlank(message = "Back Blaze Bucket Name is required")
    private String bucketName;
}

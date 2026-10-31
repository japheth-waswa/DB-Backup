package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.CloudType;
import jakarta.persistence.AttributeConverter;

/**
 * Stores {@link CloudType} by name so renaming Java constants is an explicit, visible change.
 */
public class CloudTypeConverter implements AttributeConverter<CloudType, String> {
    @Override
    public String convertToDatabaseColumn(CloudType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public CloudType convertToEntityAttribute(String dbData) {
        return dbData != null ? CloudType.valueOf(dbData) : null;
    }
}

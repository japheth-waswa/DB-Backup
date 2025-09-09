package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.CloudType;
import jakarta.persistence.AttributeConverter;

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

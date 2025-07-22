package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.persistence.AttributeConverter;

public class SchedulerTypeConverter implements AttributeConverter<SchedulerType, String> {
    @Override
    public String convertToDatabaseColumn(SchedulerType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public SchedulerType convertToEntityAttribute(String dbData) {
        return dbData != null ? SchedulerType.valueOf(dbData) : null;
    }
}

package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.SchedulerType;
import jakarta.persistence.AttributeConverter;

/**
 * Stores {@link SchedulerType} by name so renaming Java constants is an explicit, visible change.
 */
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

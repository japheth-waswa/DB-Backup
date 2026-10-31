package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.DBType;
import jakarta.persistence.AttributeConverter;

/**
 * Stores {@link DBType} by name so renaming Java constants is an explicit, visible change.
 */
public class DbTypeConverter implements AttributeConverter<DBType, String> {
    @Override
    public String convertToDatabaseColumn(DBType attribute) {
        return attribute != null ? attribute.name() : null;
    }

    @Override
    public DBType convertToEntityAttribute(String dbData) {
        return dbData != null ? DBType.valueOf(dbData) : null;
    }
}

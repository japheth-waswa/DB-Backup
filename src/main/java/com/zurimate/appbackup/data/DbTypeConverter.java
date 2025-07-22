package com.zurimate.appbackup.data;

import com.zurimate.appbackup.utils.DBType;
import jakarta.persistence.AttributeConverter;

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

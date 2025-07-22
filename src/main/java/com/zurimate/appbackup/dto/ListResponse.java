package com.zurimate.appbackup.dto;

import java.util.List;

public record ListResponse<T>(long totalCount, List<T> list) {
}

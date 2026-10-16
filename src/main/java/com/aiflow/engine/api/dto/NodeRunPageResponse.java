package com.aiflow.engine.api.dto;

import java.util.List;

public record NodeRunPageResponse(List<NodeRunSummaryResponse> items, int page, int size, boolean hasNext) {
}

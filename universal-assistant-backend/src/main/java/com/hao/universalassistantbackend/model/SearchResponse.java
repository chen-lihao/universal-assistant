package com.hao.universalassistantbackend.model;

import java.util.List;

public record SearchResponse(String query, List<SearchResult> results) {
}

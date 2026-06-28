package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.SearchResult;

import java.util.List;

public interface SearchService {
    List<SearchResult> search(String query, int limit);
}

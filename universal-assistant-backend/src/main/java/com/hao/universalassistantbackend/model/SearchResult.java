package com.hao.universalassistantbackend.model;

public record SearchResult(String title, String url, String snippet, String provider) {

    public SearchResult(String title, String url, String snippet) {
        this(title, url, snippet, "web");
    }
}

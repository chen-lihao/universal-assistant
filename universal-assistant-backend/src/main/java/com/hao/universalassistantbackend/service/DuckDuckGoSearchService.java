package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.SearchResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DuckDuckGoSearchService implements SearchService {

    private static final Pattern RESULT_LINK_PATTERN = Pattern.compile(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public List<SearchResult> search(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://duckduckgo.com/html/?q=" + encodedQuery))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Mozilla/5.0 UniversalAssistant/0.1")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }

            return parseResults(response.body(), limit);
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private List<SearchResult> parseResults(String html, int limit) {
        Map<String, SearchResult> deduped = new LinkedHashMap<>();
        Matcher matcher = RESULT_LINK_PATTERN.matcher(html);

        while (matcher.find() && deduped.size() < limit) {
            String url = normalizeDuckDuckGoUrl(htmlDecode(matcher.group(1)));
            String title = stripHtml(matcher.group(2));
            String snippet = findSnippet(html, matcher.end());

            if (isSearchResultUrl(url) && StringUtils.hasText(title)) {
                deduped.putIfAbsent(url, new SearchResult(title, url, snippet));
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private String findSnippet(String html, int startIndex) {
        int endIndex = Math.min(html.length(), startIndex + 1400);
        Matcher matcher = SNIPPET_PATTERN.matcher(html.substring(startIndex, endIndex));

        if (matcher.find()) {
            return stripHtml(matcher.group(1));
        }

        return "";
    }

    private String stripHtml(String value) {
        return htmlDecode(TAG_PATTERN.matcher(value).replaceAll(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeDuckDuckGoUrl(String value) {
        String url = value.trim();
        if (url.startsWith("//")) {
            url = "https:" + url;
        }

        if (!isSearchResultUrl(url)) {
            return "";
        }

        int uddgIndex = url.indexOf("uddg=");
        if (uddgIndex >= 0) {
            String encoded = url.substring(uddgIndex + 5);
            int ampIndex = encoded.indexOf('&');
            if (ampIndex >= 0) {
                encoded = encoded.substring(0, ampIndex);
            }
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        }

        return url;
    }

    private boolean isSearchResultUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return false;
        }

        String normalized = url.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("http")
                && !normalized.contains("duckduckgo.com/y.js")
                && !normalized.contains("ad_domain=")
                && !normalized.contains("ad_provider=")
                && !normalized.contains("ad_type=");
    }

    private String htmlDecode(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
    }
}

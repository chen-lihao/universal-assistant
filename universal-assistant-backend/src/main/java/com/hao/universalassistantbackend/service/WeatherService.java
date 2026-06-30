package com.hao.universalassistantbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hao.universalassistantbackend.model.SearchResult;
import com.hao.universalassistantbackend.model.WeatherReport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeatherService {

    private static final String GEOCODING_BASE_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_BASE_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String ARCHIVE_BASE_URL = "https://archive-api.open-meteo.com/v1/archive";
    private static final int FORECAST_MAX_DAYS = 16;
    private static final ZoneId DEFAULT_ZONE = ZoneId.systemDefault();
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern CHINESE_MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:日|号)?");
    private static final Pattern SHORT_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})[/-](\\d{1,2})(?!\\d)");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper objectMapper;

    public WeatherService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WeatherReport getCurrentWeather(String location) {
        return getWeather(location, "today");
    }

    public WeatherReport getWeather(String location, String dateText) {
        TargetDate targetDate = parseTargetDate(dateText);
        if (!StringUtils.hasText(location)) {
            return WeatherReport.unavailable(
                    location,
                    "需要先知道城市或地区名称才能查询天气。例如：广州黄埔、上海浦东、北京海淀。",
                    List.of()
            );
        }

        try {
            LocationMatch matchedLocation = findLocation(location);
            if (matchedLocation == null) {
                return WeatherReport.unavailable(
                        location,
                        "没有找到“" + location + "”对应的经纬度。请提供更明确的城市或区县名称。",
                        List.of(geocodingSource(location))
                );
            }

            if (targetDate.date().isBefore(today())) {
                return fetchHistoricalWeather(matchedLocation, targetDate);
            }

            return fetchForecast(matchedLocation, targetDate);
        } catch (IOException | InterruptedException | IllegalArgumentException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return WeatherReport.unavailable(
                        location,
                        "天气查询已中断。",
                        List.of(geocodingSource(location))
                );
            }
            return WeatherReport.unavailable(
                    location,
                    "天气服务暂时不可用：" + safeErrorMessage(ex),
                    List.of(geocodingSource(location))
            );
        }
    }

    private LocationMatch findLocation(String location) throws IOException, InterruptedException {
        for (String candidate : buildLocationCandidates(location)) {
            URI uri = URI.create(GEOCODING_BASE_URL
                    + "?name=" + encode(candidate)
                    + "&count=5&language=zh&format=json");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "UniversalAssistant/0.1")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                continue;
            }

            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (results.isArray() && !results.isEmpty()) {
                JsonNode node = results.get(0);
                return new LocationMatch(
                        text(node, "name"),
                        text(node, "admin1"),
                        text(node, "country"),
                        number(node, "latitude"),
                        number(node, "longitude"),
                        StringUtils.hasText(text(node, "timezone")) ? text(node, "timezone") : "auto",
                        candidate
                );
            }
        }

        return null;
    }

    private List<String> buildLocationCandidates(String location) {
        String normalized = location
                .replaceAll("[，。！？?、]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, normalized);

        String withoutDistrictSuffix = normalized.replace("新区", "").replace("区", "").replace("县", "").trim();
        addCandidate(candidates, withoutDistrictSuffix);

        if (normalized.contains("广州") && normalized.contains("黄埔")) {
            addCandidate(candidates, "黄埔 广州");
            addCandidate(candidates, "Huangpu Guangzhou");
            addCandidate(candidates, "Guangzhou");
        }

        return candidates;
    }

    private void addCandidate(List<String> candidates, String candidate) {
        if (StringUtils.hasText(candidate) && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private WeatherReport fetchForecast(LocationMatch location, TargetDate targetDate) throws IOException, InterruptedException {
        long daysFromToday = ChronoUnit.DAYS.between(today(), targetDate.date());
        if (daysFromToday > FORECAST_MAX_DAYS) {
            return WeatherReport.unavailable(
                    location.displayName(),
                    "天气预报服务暂时只支持未来 " + FORECAST_MAX_DAYS + " 天内的日期，目标日期 "
                            + targetDate.date() + " 需要通过实时检索补充。",
                    List.of(geocodingSource(location.query()))
            );
        }

        boolean currentDay = targetDate.date().isEqual(today());
        String currentParams = currentDay
                ? "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m"
                : "";
        URI uri = URI.create(FORECAST_BASE_URL
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + currentParams
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum"
                + "&start_date=" + targetDate.date()
                + "&end_date=" + targetDate.date()
                + "&timezone=auto");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "UniversalAssistant/0.1")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return WeatherReport.unavailable(
                    location.displayName(),
                    "天气预报接口返回异常状态：" + response.statusCode(),
                    sources(location, uri, "Forecast", "天气预报数据。")
            );
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode current = root.path("current");
        JsonNode daily = root.path("daily");
        if (!hasDailyData(daily)) {
            return WeatherReport.unavailable(
                    location.displayName(),
                    "天气预报接口没有返回 " + targetDate.date() + " 的有效数据。",
                    sources(location, uri, "Forecast", "天气预报数据。")
            );
        }

        if (!currentDay) {
            return buildDailyReport(location, targetDate, daily, uri, "Forecast", "预报");
        }

        String weatherText = weatherCodeText(current.path("weather_code").asInt(-1));
        String displayLocation = location.displayName();
        String summary = "当前 " + displayLocation + " 天气：" + weatherText
                + "，气温 " + formatNumber(current.path("temperature_2m").asDouble(Double.NaN)) + "°C"
                + "，体感 " + formatNumber(current.path("apparent_temperature").asDouble(Double.NaN)) + "°C"
                + "，湿度 " + formatNumber(current.path("relative_humidity_2m").asDouble(Double.NaN)) + "%"
                + "，降水 " + formatNumber(current.path("precipitation").asDouble(Double.NaN)) + " mm"
                + "，风速 " + formatNumber(current.path("wind_speed_10m").asDouble(Double.NaN)) + " km/h。"
                + dailySummary(daily, " 今日");

        String context = """
                Weather tool result:
                Location: %s
                Matched query: %s
                Target date: %s (%s)
                Data type: current weather and daily forecast
                Latitude: %s
                Longitude: %s
                Current weather: %s
                Current temperature: %s °C
                Apparent temperature: %s °C
                Relative humidity: %s %%
                Precipitation: %s mm
                Wind speed: %s km/h
                Wind direction: %s degrees
                Daily forecast: %s
                Source: Open-Meteo Forecast API
                """.formatted(
                displayLocation,
                location.query(),
                targetDate.date(),
                targetDate.label(),
                location.latitude(),
                location.longitude(),
                weatherText,
                formatNumber(current.path("temperature_2m").asDouble(Double.NaN)),
                formatNumber(current.path("apparent_temperature").asDouble(Double.NaN)),
                formatNumber(current.path("relative_humidity_2m").asDouble(Double.NaN)),
                formatNumber(current.path("precipitation").asDouble(Double.NaN)),
                formatNumber(current.path("wind_speed_10m").asDouble(Double.NaN)),
                formatNumber(current.path("wind_direction_10m").asDouble(Double.NaN)),
                dailyContext(daily)
        );

        return new WeatherReport(true, displayLocation, summary, context, sources(location, uri, "Forecast", "当前天气与当日预报数据。"));
    }

    private WeatherReport fetchHistoricalWeather(LocationMatch location, TargetDate targetDate) throws IOException, InterruptedException {
        URI uri = URI.create(ARCHIVE_BASE_URL
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum"
                + "&start_date=" + targetDate.date()
                + "&end_date=" + targetDate.date()
                + "&timezone=auto");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "UniversalAssistant/0.1")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return WeatherReport.unavailable(
                    location.displayName(),
                    "历史天气接口返回异常状态：" + response.statusCode(),
                    sources(location, uri, "Historical Weather", "历史天气数据。")
            );
        }

        JsonNode daily = objectMapper.readTree(response.body()).path("daily");
        if (!hasDailyData(daily)) {
            return WeatherReport.unavailable(
                    location.displayName(),
                    "历史天气接口没有返回 " + targetDate.date() + " 的有效数据。",
                    sources(location, uri, "Historical Weather", "历史天气数据。")
            );
        }

        return buildDailyReport(location, targetDate, daily, uri, "Historical Weather", "历史");
    }

    private WeatherReport buildDailyReport(LocationMatch location,
                                           TargetDate targetDate,
                                           JsonNode daily,
                                           URI uri,
                                           String sourceType,
                                           String reportType) {
        String displayLocation = location.displayName();
        String weatherText = weatherCodeText(dailyInt(daily, "weather_code"));
        double max = dailyDouble(daily, "temperature_2m_max");
        double min = dailyDouble(daily, "temperature_2m_min");
        double rain = dailyDouble(daily, "precipitation_sum");
        String sourceName = sourceType.equals("Forecast") ? "Open-Meteo Forecast API" : "Open-Meteo Historical Weather API";
        String summary = displayLocation + " " + targetDate.label() + "（" + targetDate.date() + "）"
                + reportType + "天气：" + weatherText
                + "，最高 " + formatNumber(max) + "°C"
                + "，最低 " + formatNumber(min) + "°C"
                + "，累计降水 " + formatNumber(rain) + " mm。";

        String context = """
                Weather tool result:
                Location: %s
                Matched query: %s
                Target date: %s (%s)
                Data type: daily %s weather
                Latitude: %s
                Longitude: %s
                Weather: %s
                Max temperature: %s °C
                Min temperature: %s °C
                Precipitation: %s mm
                Source: %s
                """.formatted(
                displayLocation,
                location.query(),
                targetDate.date(),
                targetDate.label(),
                reportType,
                location.latitude(),
                location.longitude(),
                weatherText,
                formatNumber(max),
                formatNumber(min),
                formatNumber(rain),
                sourceName
        );

        return new WeatherReport(true, displayLocation, summary, context, sources(location, uri, sourceType, reportType + "天气数据。"));
    }

    private String dailySummary(JsonNode daily, String prefix) {
        if (daily == null || daily.isMissingNode()) {
            return "";
        }

        JsonNode max = daily.path("temperature_2m_max");
        JsonNode min = daily.path("temperature_2m_min");
        JsonNode rain = daily.path("precipitation_sum");
        if (!max.isArray() || max.isEmpty()) {
            return "";
        }

        return prefix + "最高 " + formatNumber(max.get(0).asDouble(Double.NaN)) + "°C"
                + "，最低 " + formatNumber(min.get(0).asDouble(Double.NaN)) + "°C"
                + "，累计降水 " + formatNumber(rain.get(0).asDouble(Double.NaN)) + " mm。";
    }

    private String dailyContext(JsonNode daily) {
        if (daily == null || daily.isMissingNode()) {
            return "unavailable";
        }

        JsonNode max = daily.path("temperature_2m_max");
        JsonNode min = daily.path("temperature_2m_min");
        JsonNode rain = daily.path("precipitation_sum");
        if (!max.isArray() || max.isEmpty()) {
            return "unavailable";
        }

        return "max " + formatNumber(max.get(0).asDouble(Double.NaN)) + " °C, min "
                + formatNumber(min.get(0).asDouble(Double.NaN)) + " °C, precipitation "
                + formatNumber(rain.get(0).asDouble(Double.NaN)) + " mm";
    }

    private boolean hasDailyData(JsonNode daily) {
        JsonNode max = daily == null ? null : daily.path("temperature_2m_max");
        return max != null && max.isArray() && !max.isEmpty();
    }

    private double dailyDouble(JsonNode daily, String field) {
        JsonNode values = daily.path(field);
        if (!values.isArray() || values.isEmpty()) {
            return Double.NaN;
        }

        return values.get(0).asDouble(Double.NaN);
    }

    private int dailyInt(JsonNode daily, String field) {
        JsonNode values = daily.path(field);
        if (!values.isArray() || values.isEmpty()) {
            return -1;
        }

        return values.get(0).asInt(-1);
    }

    private List<SearchResult> sources(LocationMatch location, URI weatherUri, String sourceType, String description) {
        return List.of(
                new SearchResult("Open-Meteo " + sourceType + " - " + location.displayName(), weatherUri.toString(), description, "open-meteo"),
                geocodingSource(location.query())
        );
    }

    private SearchResult geocodingSource(String location) {
        return new SearchResult(
                "Open-Meteo Geocoding - " + location,
                GEOCODING_BASE_URL + "?name=" + encode(location) + "&count=5&language=zh&format=json",
                "地点经纬度匹配来源。",
                "open-meteo"
        );
    }

    private String weatherCodeText(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1, 2 -> "少云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷雨";
            case 96, 99 -> "雷雨伴冰雹";
            default -> "未知天气";
        };
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private double number(JsonNode node, String field) {
        return node.path(field).asDouble(Double.NaN);
    }

    private String formatNumber(double value) {
        if (Double.isNaN(value)) {
            return "未知";
        }

        return String.format(Locale.ROOT, "%.1f", value);
    }

    private TargetDate parseTargetDate(String dateText) {
        LocalDate currentDate = today();
        if (!StringUtils.hasText(dateText)) {
            return new TargetDate(currentDate, "今天");
        }

        String normalized = dateText.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("后天")) {
            return new TargetDate(currentDate.plusDays(2), "后天");
        }
        if (normalized.contains("明天") || normalized.contains("tomorrow")) {
            return new TargetDate(currentDate.plusDays(1), "明天");
        }
        if (normalized.contains("前天")) {
            return new TargetDate(currentDate.minusDays(2), "前天");
        }
        if (normalized.contains("昨天") || normalized.contains("yesterday")) {
            return new TargetDate(currentDate.minusDays(1), "昨天");
        }
        if (normalized.contains("今天")
                || normalized.contains("今日")
                || normalized.contains("现在")
                || normalized.contains("当前")
                || normalized.contains("today")
                || normalized.contains("current")
                || normalized.contains("now")) {
            return new TargetDate(currentDate, "今天");
        }

        Matcher isoMatcher = ISO_DATE_PATTERN.matcher(normalized);
        if (isoMatcher.find()) {
            try {
                LocalDate parsed = LocalDate.parse(isoMatcher.group(1));
                return new TargetDate(parsed, isoMatcher.group(1));
            } catch (RuntimeException ignored) {
                return new TargetDate(currentDate, "今天");
            }
        }

        Matcher chineseMatcher = CHINESE_MONTH_DAY_PATTERN.matcher(normalized);
        if (chineseMatcher.find()) {
            return monthDayTarget(currentDate, chineseMatcher.group(1), chineseMatcher.group(2));
        }

        Matcher shortMatcher = SHORT_MONTH_DAY_PATTERN.matcher(normalized);
        if (shortMatcher.find()) {
            return monthDayTarget(currentDate, shortMatcher.group(1), shortMatcher.group(2));
        }

        return new TargetDate(currentDate, "今天");
    }

    private TargetDate monthDayTarget(LocalDate currentDate, String monthText, String dayText) {
        try {
            int month = Integer.parseInt(monthText);
            int day = Integer.parseInt(dayText);
            LocalDate parsed = LocalDate.of(currentDate.getYear(), month, day);
            return new TargetDate(parsed, parsed.toString());
        } catch (RuntimeException ignored) {
            return new TargetDate(currentDate, "今天");
        }
    }

    private LocalDate today() {
        return LocalDate.now(DEFAULT_ZONE);
    }

    private String safeErrorMessage(Exception ex) {
        return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record TargetDate(LocalDate date, String label) {
    }

    private record LocationMatch(String name, String admin1, String country, double latitude, double longitude, String timezone, String query) {

        private String displayName() {
            List<String> parts = new ArrayList<>();
            if (StringUtils.hasText(name)) {
                parts.add(name);
            }
            if (StringUtils.hasText(admin1) && !parts.contains(admin1)) {
                parts.add(admin1);
            }
            if (StringUtils.hasText(country) && !parts.contains(country)) {
                parts.add(country);
            }
            return String.join("，", parts);
        }
    }
}

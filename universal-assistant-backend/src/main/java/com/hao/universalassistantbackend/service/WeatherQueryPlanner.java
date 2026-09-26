package com.hao.universalassistantbackend.service;

import com.hao.universalassistantbackend.model.ChatMessage;
import com.hao.universalassistantbackend.model.WeatherPlan;
import com.hao.universalassistantbackend.model.WeatherQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WeatherQueryPlanner {

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_QUERIES = 12;
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CHINESE_MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:日|号)?");
    private static final Pattern SHORT_MONTH_DAY_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})[/-](\\d{1,2})(?!\\d)");
    private static final Pattern FUTURE_DAYS_PATTERN = Pattern.compile("未来\\s*([一二两三四五六七八九十\\d]+)\\s*天");
    private static final Pattern FUTURE_SOME_DAYS_PATTERN = Pattern.compile("(?:未来|接下来|随后|后续)\\s*几\\s*天");
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("(?:本周|这周|下周|周|星期)([一二三四五六日天])");

    private static final Map<String, String> POI_TO_REGION = orderedMap(
            "陈家祠", "广州荔湾",
            "永庆坊", "广州荔湾",
            "上下九", "广州荔湾",
            "沙面", "广州荔湾",
            "宝华路", "广州荔湾",
            "石室圣心大教堂", "广州越秀",
            "圣心大教堂", "广州越秀",
            "北京路", "广州越秀",
            "越秀公园", "广州越秀",
            "广州塔", "广州海珠",
            "小蛮腰", "广州海珠",
            "珠江夜游", "广州海珠",
            "海心沙", "广州天河",
            "花城广场", "广州天河",
            "珠江新城", "广州天河",
            "广东省博物馆", "广州天河",
            "白云山", "广州白云",
            "长隆", "广州番禺",
            "黄埔军校", "广州黄埔"
    );

    private static final Map<String, String> DISTRICT_TO_LOCATION = orderedMap(
            "广州黄埔", "广州黄埔",
            "黄埔", "广州黄埔",
            "广州天河", "广州天河",
            "天河", "广州天河",
            "广州海珠", "广州海珠",
            "海珠", "广州海珠",
            "广州荔湾", "广州荔湾",
            "荔湾", "广州荔湾",
            "广州越秀", "广州越秀",
            "越秀", "广州越秀",
            "广州白云", "广州白云",
            "白云", "广州白云",
            "广州番禺", "广州番禺",
            "番禺", "广州番禺",
            "广州南沙", "广州南沙",
            "南沙", "广州南沙",
            "广州花都", "广州花都",
            "花都", "广州花都",
            "广州增城", "广州增城",
            "增城", "广州增城",
            "广州从化", "广州从化",
            "从化", "广州从化",
            "广州", "广州"
    );

    public WeatherPlan plan(String message, List<ChatMessage> history, String conversationSummary) {
        boolean weatherIntent = hasWeatherIntent(message);
        if (!weatherIntent) {
            return WeatherPlan.empty();
        }

        List<DateCandidate> dates = extractDates(message);
        List<LocationCandidate> locations = extractLocations(message, history, conversationSummary);
        if (locations.isEmpty()) {
            return new WeatherPlan(
                    true,
                    List.of(),
                    List.of(),
                    "没有找到可直接用于天气工具的地点。",
                    true,
                    "我需要先确认要查询的地点。你是指上一条行程里的景点，还是有其他城市/景点？",
                    false
            );
        }

        Map<String, WeatherQuery> deduped = new LinkedHashMap<>();
        boolean truncated = false;
        for (LocationCandidate location : locations) {
            for (DateCandidate date : dates) {
                String key = location.resolvedLocation() + "|" + date.targetDate();
                if (deduped.containsKey(key)) {
                    continue;
                }
                if (deduped.size() >= MAX_QUERIES) {
                    truncated = true;
                    continue;
                }
                deduped.put(key, new WeatherQuery(
                        location.locationText(),
                        location.resolvedLocation(),
                        date.label(),
                        date.targetDate().toString(),
                        location.source(),
                        location.confidence()
                ));
            }
        }

        return new WeatherPlan(
                true,
                List.copyOf(deduped.values()),
                locations.stream().map(LocationCandidate::locationText).distinct().toList(),
                hasContextReference(message) ? "问题包含指代词，已结合最近会话中的景点/路线解析地点。" : "",
                false,
                "",
                truncated
        );
    }

    private List<LocationCandidate> extractLocations(String message, List<ChatMessage> history, String conversationSummary) {
        Map<String, LocationCandidate> locations = new LinkedHashMap<>();
        collectLocationsFromText(message, "user_input", locations);

        if (locations.isEmpty() && hasContextReference(message)) {
            String contextText = contextText(history, conversationSummary);
            collectLocationsFromText(contextText, "conversation_context", locations);
        }

        if (locations.isEmpty()) {
            String cleaned = cleanLocationText(message);
            if (StringUtils.hasText(cleaned) && cleaned.length() <= 12 && !isVagueLocation(cleaned)) {
                locations.put(cleaned, new LocationCandidate(cleaned, cleaned, "user_input", 0.55));
            }
        }

        return new ArrayList<>(locations.values());
    }

    private void collectLocationsFromText(String text, String source, Map<String, LocationCandidate> locations) {
        if (!StringUtils.hasText(text)) {
            return;
        }

        for (Map.Entry<String, String> entry : POI_TO_REGION.entrySet()) {
            if (text.contains(entry.getKey())) {
                addLocation(locations, entry.getKey(), entry.getValue(), source.equals("user_input") ? "derived_from_poi" : source, 0.9);
            }
        }

        for (Map.Entry<String, String> entry : DISTRICT_TO_LOCATION.entrySet()) {
            if ("广州".equals(entry.getKey()) && hasSpecificLocation(locations)) {
                continue;
            }
            if (text.contains(entry.getKey())) {
                addLocation(locations, entry.getKey(), entry.getValue(), source, "广州".equals(entry.getValue()) ? 0.65 : 0.82);
            }
        }
    }

    private boolean hasSpecificLocation(Map<String, LocationCandidate> locations) {
        return locations.values().stream()
                .anyMatch(location -> !"广州".equals(location.resolvedLocation()));
    }

    private void addLocation(Map<String, LocationCandidate> locations,
                             String locationText,
                             String resolvedLocation,
                             String source,
                             double confidence) {
        String key = resolvedLocation;
        LocationCandidate existing = locations.get(key);
        if (existing == null || confidence > existing.confidence()) {
            locations.put(key, new LocationCandidate(locationText, resolvedLocation, source, confidence));
        }
    }

    private List<DateCandidate> extractDates(String message) {
        LocalDate today = LocalDate.now(APP_ZONE);
        Map<String, DateCandidate> dates = new LinkedHashMap<>();
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);

        Matcher futureMatcher = FUTURE_DAYS_PATTERN.matcher(normalized);
        if (futureMatcher.find()) {
            int days = Math.max(1, Math.min(7, parseChineseNumber(futureMatcher.group(1), 3)));
            for (int i = 0; i < days; i++) {
                LocalDate date = today.plusDays(i);
                dates.put(date.toString(), new DateCandidate(i == 0 ? "今天" : date.toString(), date));
            }
        }
        if (dates.isEmpty() && FUTURE_SOME_DAYS_PATTERN.matcher(normalized).find()) {
            for (int i = 0; i < 3; i++) {
                LocalDate date = today.plusDays(i);
                dates.put(date.toString(), new DateCandidate(i == 0 ? "今天" : date.toString(), date));
            }
        }

        if (normalized.contains("明后天")) {
            dates.put(today.plusDays(1).toString(), new DateCandidate("明天", today.plusDays(1)));
            dates.put(today.plusDays(2).toString(), new DateCandidate("后天", today.plusDays(2)));
        }
        if (normalized.contains("后天")) {
            dates.put(today.plusDays(2).toString(), new DateCandidate("后天", today.plusDays(2)));
        }
        if (normalized.contains("明天") || normalized.contains("tomorrow")) {
            dates.put(today.plusDays(1).toString(), new DateCandidate("明天", today.plusDays(1)));
        }
        if (normalized.contains("前天")) {
            dates.put(today.minusDays(2).toString(), new DateCandidate("前天", today.minusDays(2)));
        }
        if (normalized.contains("昨天") || normalized.contains("yesterday")) {
            dates.put(today.minusDays(1).toString(), new DateCandidate("昨天", today.minusDays(1)));
        }
        if (normalized.contains("今天")
                || normalized.contains("今日")
                || normalized.contains("现在")
                || normalized.contains("当前")
                || normalized.contains("today")
                || normalized.contains("current")
                || normalized.contains("now")) {
            dates.put(today.toString(), new DateCandidate("今天", today));
        }

        Matcher isoMatcher = ISO_DATE_PATTERN.matcher(normalized);
        while (isoMatcher.find()) {
            tryAddDate(dates, isoMatcher.group(), LocalDate.parse(isoMatcher.group()));
        }

        Matcher chineseMatcher = CHINESE_MONTH_DAY_PATTERN.matcher(normalized);
        while (chineseMatcher.find()) {
            addMonthDay(dates, today, chineseMatcher.group(), chineseMatcher.group(1), chineseMatcher.group(2));
        }

        Matcher shortMatcher = SHORT_MONTH_DAY_PATTERN.matcher(normalized);
        while (shortMatcher.find()) {
            addMonthDay(dates, today, shortMatcher.group(), shortMatcher.group(1), shortMatcher.group(2));
        }

        Matcher weekdayMatcher = WEEKDAY_PATTERN.matcher(normalized);
        while (weekdayMatcher.find()) {
            LocalDate date = nextWeekday(today, weekdayMatcher.group(1), weekdayMatcher.group().startsWith("下周"));
            tryAddDate(dates, weekdayMatcher.group(), date);
        }

        if (dates.isEmpty()) {
            dates.put(today.toString(), new DateCandidate("今天", today));
        }

        return new ArrayList<>(dates.values());
    }

    private void addMonthDay(Map<String, DateCandidate> dates, LocalDate today, String label, String monthText, String dayText) {
        try {
            LocalDate date = LocalDate.of(today.getYear(), Integer.parseInt(monthText), Integer.parseInt(dayText));
            tryAddDate(dates, label, date);
        } catch (RuntimeException ignored) {
            // Ignore invalid date text.
        }
    }

    private void tryAddDate(Map<String, DateCandidate> dates, String label, LocalDate date) {
        if (date != null) {
            dates.put(date.toString(), new DateCandidate(label, date));
        }
    }

    private LocalDate nextWeekday(LocalDate today, String weekdayText, boolean nextWeek) {
        DayOfWeek target = switch (weekdayText) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
        int delta = target.getValue() - today.getDayOfWeek().getValue();
        if (delta < 0 || nextWeek) {
            delta += 7;
        }
        return today.plusDays(delta);
    }

    private int parseChineseNumber(String value, int fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return switch (value.trim()) {
                case "一" -> 1;
                case "二", "两" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                case "六" -> 6;
                case "七" -> 7;
                case "八" -> 8;
                case "九" -> 9;
                case "十" -> 10;
                default -> fallback;
            };
        }
    }

    private boolean hasWeatherIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("天气")
                || normalized.contains("气温")
                || normalized.contains("温度")
                || normalized.contains("下雨")
                || normalized.contains("降雨")
                || normalized.contains("降水")
                || normalized.contains("预报")
                || normalized.contains("weather")
                || normalized.contains("forecast")
                || normalized.contains("temperature");
    }

    private boolean hasContextReference(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        return message.contains("这些")
                || message.contains("上述")
                || message.contains("上面")
                || message.contains("上一轮")
                || message.contains("上轮")
                || message.contains("刚才")
                || message.contains("前面")
                || message.contains("行程")
                || message.contains("路线")
                || message.contains("景点")
                || message.contains("它们")
                || message.contains("这些地方")
                || message.contains("这里")
                || message.contains("那里")
                || message.contains("附近")
                || message.contains("当地")
                || message.contains("本地");
    }

    private boolean isVagueLocation(String value) {
        return List.of("这里", "那里", "这儿", "那儿", "附近", "当地", "本地", "这个地方", "上述地区")
                .contains(value);
    }

    private String contextText(List<ChatMessage> history, String conversationSummary) {
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(conversationSummary)) {
            text.append(conversationSummary).append('\n');
        }
        if (history != null) {
            history.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.content()))
                    .skip(Math.max(0, history.size() - 8))
                    .forEach(item -> text.append(item.content()).append('\n'));
        }
        return text.toString();
    }

    private String cleanLocationText(String message) {
        if (!StringUtils.hasText(message)) {
            return "";
        }
        return message
                .replaceAll("(?i)weather|forecast|temperature|today|tomorrow|yesterday|current|now", " ")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*(?:日|号)?|(?<!\\d)\\d{1,2}[/-]\\d{1,2}(?!\\d)", " ")
                .replaceAll("今天|今日|明天|后天|昨天|前天|现在|当前|实时|天气|气温|温度|下雨|降雨|降水|预报|怎么样|如何|查询|查一下|帮我|请问|请|的|吗|呢", " ")
                .replaceAll("[，。！？?、,.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Map<String, String> orderedMap(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private record LocationCandidate(String locationText, String resolvedLocation, String source, double confidence) {
    }

    private record DateCandidate(String label, LocalDate targetDate) {
    }
}

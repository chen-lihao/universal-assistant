package com.hao.universalassistantbackend.tools;

import com.hao.universalassistantbackend.model.WeatherReport;
import com.hao.universalassistantbackend.service.WeatherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {

    private final WeatherService weatherService;

    public WeatherTools(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(
            name = "get_weather",
            description = "Get current, forecast, or historical weather for a city, district, or location. Use this for weather, temperature, rain, wind, humidity, forecast, or historical weather questions. The date can be today, tomorrow, yesterday, 2026-06-29, or Chinese expressions like 今天、明天、昨天、6月30日."
    )
    public String getWeather(
            @ToolParam(description = "City, district, or location name, for example: 广州黄埔, 上海浦东, 北京海淀.")
            String location,
            @ToolParam(required = false, description = "Target date or relative date, for example: today, tomorrow, yesterday, 2026-06-29, 今天, 明天, 昨天, 6月30日.")
            String date
    ) {
        WeatherReport report = weatherService.getWeather(location, date);
        return report.context();
    }

    public WeatherReport getWeatherReport(String location, String date) {
        return weatherService.getWeather(location, date);
    }
}

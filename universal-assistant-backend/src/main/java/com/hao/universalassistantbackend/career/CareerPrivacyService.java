package com.hao.universalassistantbackend.career;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

@Service
public class CareerPrivacyService {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b"
    );
    private static final Pattern MOBILE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"
    );
    private static final Pattern NATIONAL_ID = Pattern.compile(
            "(?<!\\d)\\d{17}[\\dXx](?![A-Za-z0-9])"
    );
    private static final Pattern ADDRESS = Pattern.compile(
            "(?m)(现居住地|居住地|住址|地址|所在地)[：:]\\s*[^\\r\\n]{2,80}"
    );
    private static final Pattern SOCIAL_ID = Pattern.compile(
            "(?im)(微信|WeChat|QQ)[：:]\\s*[^\\s,，;；]{2,60}"
    );

    public String redactForExternalProcessing(String content) {
        if (!StringUtils.hasText(content)) {
            return content == null ? "" : content;
        }
        String redacted = EMAIL.matcher(content).replaceAll("[已隐藏邮箱]");
        redacted = MOBILE.matcher(redacted).replaceAll("[已隐藏手机号]");
        redacted = NATIONAL_ID.matcher(redacted).replaceAll("[已隐藏证件号]");
        redacted = ADDRESS.matcher(redacted).replaceAll("$1：[已隐藏详细地址]");
        return SOCIAL_ID.matcher(redacted).replaceAll("$1：[已隐藏账号]");
    }
}

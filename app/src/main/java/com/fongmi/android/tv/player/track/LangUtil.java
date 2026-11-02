package com.fongmi.android.tv.player.track;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LangUtil {

    private static final int SCORE_EXACT = 400;
    private static final int SCORE_SUBTAG = 300;
    private static final int SCORE_PRIMARY = 200;
    private static final int SCORE_RELATED = 100;
    private static final String LANGUAGE_CHINESE = "zh";
    private static final String SCRIPT_HANS = "Hans";
    private static final String SCRIPT_HANT = "Hant";
    private static final String TAG_HANS = "zh-Hans";
    private static final String TAG_HANT = "zh-Hant";

    public static String[] getPreferredTextLanguages() {
        // Shared fixed order for EXO and MPV. Never let the device region prefer Hant.
        return unique("zh-Hans", "zh-CN", "chs", "sc", "zh", "zh-Hant", "zh-TW", "cht");
    }

    public static int getPreferredTextLanguageScore(String languageTag) {
        String language = normalize(languageTag);
        if (language.isEmpty()) return 0;
        if (language.startsWith("zh-hans") || language.equals("zh-cn")
                || language.equals("zh-sg") || language.equals("chs") || language.equals("sc")) return 400;
        if (language.equals("zh")) return 200;
        if (language.startsWith("zh-hant") || language.equals("zh-tw")
                || language.equals("zh-hk") || language.equals("zh-mo") || language.equals("cht")) return 100;
        return 0;
    }

    private static boolean isChinese(Locale locale) {
        return LANGUAGE_CHINESE.equals(locale.getLanguage());
    }

    private static boolean isTraditionalChinese(Locale locale) {
        String script = locale.getScript();
        if (SCRIPT_HANT.equalsIgnoreCase(script)) return true;
        if (SCRIPT_HANS.equalsIgnoreCase(script)) return false;
        String country = locale.getCountry();
        return "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country);
    }

    private static String getChineseScript(Locale locale) {
        return isTraditionalChinese(locale) ? TAG_HANT : TAG_HANS;
    }

    private static int getChineseScore(Locale locale, String language) {
        Locale trackLocale = Locale.forLanguageTag(language);
        if (!LANGUAGE_CHINESE.equals(trackLocale.getLanguage())) return 0;
        if (language.equals(LANGUAGE_CHINESE)) return SCORE_PRIMARY;
        return isTraditionalChinese(locale) == isTraditionalChinese(trackLocale) ? SCORE_SUBTAG : SCORE_RELATED;
    }

    private static int getLanguageScore(String preferred, String preferredLanguage, String language) {
        String trackLanguage = Locale.forLanguageTag(language).getLanguage();
        if (!preferredLanguage.equals(trackLanguage)) return 0;
        return isTagPrefix(preferred, language) || isTagPrefix(language, preferred) ? SCORE_SUBTAG : SCORE_PRIMARY;
    }

    private static boolean isTagPrefix(String tag, String prefix) {
        return tag.equals(prefix) || tag.startsWith(prefix + "-");
    }

    private static String normalize(String language) {
        return language == null ? "" : language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String[] unique(String... languages) {
        List<String> result = new ArrayList<>();
        for (String language : languages) if (!result.contains(language)) result.add(language);
        return result.toArray(new String[0]);
    }
}

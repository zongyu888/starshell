package com.aifinalshell.config;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 国际化管理器（单例）。
 * <p>封装 {@link ResourceBundle}，提供按语言切换的能力。
 * 语言值约定："zh_CN" / "en"。FXML 可通过 {@code %key} 语法引用，
 * Java 代码通过 {@link #tr(String)} 获取本地化字符串。</p>
 */
public class I18n {
    private static I18n instance;
    private ResourceBundle bundle;
    private String language;

    private I18n() {
        setLanguage(AppConfig.getInstance().getLanguage());
    }

    public static synchronized I18n getInstance() {
        if (instance == null) {
            instance = new I18n();
        }
        return instance;
    }

    /** 设置当前语言并重新加载资源包。lang: "zh_CN" 或 "en" */
    public void setLanguage(String lang) {
        this.language = (lang == null || lang.isEmpty()) ? "zh_CN" : lang;
        Locale locale = "en".equalsIgnoreCase(this.language) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE;
        try {
            this.bundle = ResourceBundle.getBundle("messages", locale);
        } catch (MissingResourceException e) {
            // 回退：仅用默认 locale
            try {
                this.bundle = ResourceBundle.getBundle("messages");
            } catch (MissingResourceException e2) {
                this.bundle = null;
            }
        }
    }

    public ResourceBundle getBundle() {
        return bundle;
    }

    public String getLanguage() {
        return language;
    }

    /** 获取本地化字符串，找不到时返回 key 本身。 */
    public String getString(String key) {
        if (bundle != null) {
            try {
                return bundle.getString(key);
            } catch (MissingResourceException e) {
                return key;
            }
        }
        return key;
    }

    /** 静态快捷方法：I18n.tr("key") */
    public static String tr(String key) {
        return getInstance().getString(key);
    }
}

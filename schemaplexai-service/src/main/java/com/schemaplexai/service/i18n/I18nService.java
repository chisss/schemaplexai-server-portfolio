package com.schemaplexai.service.i18n;

import com.schemaplexai.model.entity.I18nLocale;

import java.util.List;
import java.util.Map;

/**
 * 国际化服务接口
 */
public interface I18nService {

    /**
     * 获取所有启用的语言列表
     */
    List<I18nLocale> getLocales();

    /**
     * 获取指定语言的翻译文案（嵌套Map结构）
     */
    Map<String, Object> getMessages(String locale);
}

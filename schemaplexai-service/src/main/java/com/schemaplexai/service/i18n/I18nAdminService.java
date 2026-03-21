package com.schemaplexai.service.i18n;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.model.entity.I18nLocale;
import com.schemaplexai.model.entity.I18nMessage;

import java.util.List;

/**
 * 国际化管理服务接口（管理端 CRUD，含缓存失效）
 */
public interface I18nAdminService {

    // ── 语言管理 ──────────────────────────────────────────────────────────────

    List<I18nLocale> listLocales();

    I18nLocale createLocale(I18nLocale locale);

    I18nLocale updateLocale(Long id, I18nLocale locale);

    void deleteLocale(Long id);

    // ── 文案管理 ──────────────────────────────────────────────────────────────

    Page<I18nMessage> pageMessages(String locale, String msgKeyLike, int pageNum, int pageSize);

    I18nMessage createMessage(I18nMessage msg);

    I18nMessage updateMessage(Long id, I18nMessage msg);

    void deleteMessage(Long id);
}

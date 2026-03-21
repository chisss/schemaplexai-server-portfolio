package com.schemaplexai.service.i18n.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.I18nLocaleMapper;
import com.schemaplexai.dao.mapper.I18nMessageMapper;
import com.schemaplexai.model.entity.I18nLocale;
import com.schemaplexai.model.entity.I18nMessage;
import com.schemaplexai.service.i18n.I18nAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 国际化管理服务实现（管理端 CRUD + Redis 缓存失效）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nAdminServiceImpl implements I18nAdminService {

    private static final String CACHE_KEY_PREFIX = "sf:i18n:messages:";

    private final I18nLocaleMapper i18nLocaleMapper;
    private final I18nMessageMapper i18nMessageMapper;
    private final StringRedisTemplate redisTemplate;

    // ── 语言管理 ──────────────────────────────────────────────────────────────

    @Override
    public List<I18nLocale> listLocales() {
        return i18nLocaleMapper.selectList(
                new LambdaQueryWrapper<I18nLocale>().orderByAsc(I18nLocale::getSortOrder));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nLocale createLocale(I18nLocale locale) {
        if (!StringUtils.hasText(locale.getCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "语言代码不能为空");
        }
        if (existsByCode(locale.getCode(), null)) {
            throw new BusinessException(ResultCode.FAIL, "语言代码已存在: " + locale.getCode());
        }
        i18nLocaleMapper.insert(locale);
        log.info("创建语言: id={}, code={}", locale.getId(), locale.getCode());
        return i18nLocaleMapper.selectById(locale.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nLocale updateLocale(Long id, I18nLocale locale) {
        I18nLocale existing = requireLocaleExists(id);
        if (StringUtils.hasText(locale.getCode()) && !locale.getCode().equals(existing.getCode())) {
            if (existsByCode(locale.getCode(), id)) {
                throw new BusinessException(ResultCode.FAIL, "语言代码已存在: " + locale.getCode());
            }
        }
        // 修改后旧 code 的缓存需要失效
        String oldCode = existing.getCode();
        I18nLocale patch = new I18nLocale();
        patch.setId(id);
        if (StringUtils.hasText(locale.getCode()))     patch.setCode(locale.getCode());
        if (StringUtils.hasText(locale.getName()))     patch.setName(locale.getName());
        if (locale.getFlag() != null)                  patch.setFlag(locale.getFlag());
        if (locale.getEnabled() != null)               patch.setEnabled(locale.getEnabled());
        if (locale.getSortOrder() != null)             patch.setSortOrder(locale.getSortOrder());
        i18nLocaleMapper.updateById(patch);
        evictCache(oldCode);
        log.info("更新语言: id={}", id);
        return i18nLocaleMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLocale(Long id) {
        I18nLocale existing = requireLocaleExists(id);
        long messageCount = i18nMessageMapper.selectCount(
                new LambdaQueryWrapper<I18nMessage>().eq(I18nMessage::getLocale, existing.getCode()));
        if (messageCount > 0) {
            throw new BusinessException(ResultCode.FAIL,
                    "请先删除该语言下的所有文案（共 " + messageCount + " 条）再删除语言");
        }
        i18nLocaleMapper.deleteById(id);
        evictCache(existing.getCode());
        log.info("删除语言: id={}, code={}", id, existing.getCode());
    }

    // ── 文案管理 ──────────────────────────────────────────────────────────────

    @Override
    public Page<I18nMessage> pageMessages(String locale, String msgKeyLike, int pageNum, int pageSize) {
        LambdaQueryWrapper<I18nMessage> wrapper = new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getLocale, locale)
                .like(StringUtils.hasText(msgKeyLike), I18nMessage::getMsgKey, msgKeyLike)
                .orderByAsc(I18nMessage::getMsgKey);
        return i18nMessageMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nMessage createMessage(I18nMessage msg) {
        if (!StringUtils.hasText(msg.getLocale()) || !StringUtils.hasText(msg.getMsgKey())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "locale 和 msgKey 不能为空");
        }
        requireLocaleByCode(msg.getLocale());
        if (existsMessage(msg.getLocale(), msg.getMsgKey(), null)) {
            throw new BusinessException(ResultCode.FAIL,
                    "该语言下 msgKey 已存在: " + msg.getMsgKey());
        }
        i18nMessageMapper.insert(msg);
        evictCache(msg.getLocale());
        log.info("创建文案: id={}, locale={}, msgKey={}", msg.getId(), msg.getLocale(), msg.getMsgKey());
        return i18nMessageMapper.selectById(msg.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public I18nMessage updateMessage(Long id, I18nMessage msg) {
        I18nMessage existing = requireMessageExists(id);
        I18nMessage patch = new I18nMessage();
        patch.setId(id);
        if (StringUtils.hasText(msg.getMsgValue()))    patch.setMsgValue(msg.getMsgValue());
        if (msg.getDescription() != null)              patch.setDescription(msg.getDescription());
        i18nMessageMapper.updateById(patch);
        evictCache(existing.getLocale());
        log.info("更新文案: id={}", id);
        return i18nMessageMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessage(Long id) {
        I18nMessage existing = requireMessageExists(id);
        i18nMessageMapper.deleteById(id);
        evictCache(existing.getLocale());
        log.info("删除文案: id={}", id);
    }

    // ── 私有辅助方法 ──────────────────────────────────────────────────────────

    private I18nLocale requireLocaleExists(Long id) {
        I18nLocale locale = i18nLocaleMapper.selectById(id);
        if (locale == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "语言不存在");
        }
        return locale;
    }

    private void requireLocaleByCode(String code) {
        Long count = i18nLocaleMapper.selectCount(
                new LambdaQueryWrapper<I18nLocale>().eq(I18nLocale::getCode, code));
        if (count == 0) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "语言不存在: " + code);
        }
    }

    private I18nMessage requireMessageExists(Long id) {
        I18nMessage msg = i18nMessageMapper.selectById(id);
        if (msg == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "文案不存在");
        }
        return msg;
    }

    private boolean existsByCode(String code, Long excludeId) {
        LambdaQueryWrapper<I18nLocale> wrapper = new LambdaQueryWrapper<I18nLocale>()
                .eq(I18nLocale::getCode, code);
        if (excludeId != null) {
            wrapper.ne(I18nLocale::getId, excludeId);
        }
        return i18nLocaleMapper.selectCount(wrapper) > 0;
    }

    private boolean existsMessage(String locale, String msgKey, Long excludeId) {
        LambdaQueryWrapper<I18nMessage> wrapper = new LambdaQueryWrapper<I18nMessage>()
                .eq(I18nMessage::getLocale, locale)
                .eq(I18nMessage::getMsgKey, msgKey);
        if (excludeId != null) {
            wrapper.ne(I18nMessage::getId, excludeId);
        }
        return i18nMessageMapper.selectCount(wrapper) > 0;
    }

    private void evictCache(String locale) {
        redisTemplate.delete(CACHE_KEY_PREFIX + locale);
        log.debug("清除i18n缓存: locale={}", locale);
    }
}

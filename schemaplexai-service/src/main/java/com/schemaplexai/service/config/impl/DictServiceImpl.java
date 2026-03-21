package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.SysDictItemMapper;
import com.schemaplexai.dao.mapper.SysDictMapper;
import com.schemaplexai.model.entity.SysDict;
import com.schemaplexai.model.entity.SysDictItem;
import com.schemaplexai.service.config.DictService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 系统字典管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictServiceImpl implements DictService {

    private static final String DEFAULT_STATUS = "active";
    private static final List<String> VALID_STATUSES = List.of("active", "inactive");

    private final SysDictMapper sysDictMapper;
    private final SysDictItemMapper sysDictItemMapper;

    @Override
    public List<SysDict> listDicts(String type) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<SysDict>()
                .orderByAsc(SysDict::getDictCode);
        if (StringUtils.hasText(type)) {
            wrapper.eq(SysDict::getDictType, type);
        }
        return sysDictMapper.selectList(wrapper);
    }

    @Override
    public List<SysDictItem> listItemsByDictCode(String code) {
        SysDict dict = requireDictByCode(code);
        return sysDictItemMapper.selectList(
                new LambdaQueryWrapper<SysDictItem>()
                        .eq(SysDictItem::getDictId, dict.getId())
                        .eq(SysDictItem::getStatus, DEFAULT_STATUS)
                        .orderByAsc(SysDictItem::getSortOrder)
                        .orderByAsc(SysDictItem::getCreatedAt));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDict createDict(SysDict dict) {
        validateDictType(dict.getDictType());
        if (existsByDictCode(dict.getDictCode(), null)) {
            throw new BusinessException(ResultCode.FAIL, "字典编码已存在: " + dict.getDictCode());
        }
        if (!StringUtils.hasText(dict.getStatus())) {
            dict.setStatus(DEFAULT_STATUS);
        } else {
            validateStatus(dict.getStatus());
        }
        sysDictMapper.insert(dict);
        log.info("创建字典: dictId={}, dictCode={}", dict.getId(), dict.getDictCode());
        return sysDictMapper.selectById(dict.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDict updateDict(String id, SysDict dict) {
        SysDict existing = requireDictExists(id);
        boolean dictCodeChanged = StringUtils.hasText(dict.getDictCode())
                && !dict.getDictCode().equals(existing.getDictCode());
        if (dictCodeChanged && existsByDictCode(dict.getDictCode(), id)) {
            throw new BusinessException(ResultCode.FAIL, "字典编码已存在: " + dict.getDictCode());
        }
        if (StringUtils.hasText(dict.getDictType())) {
            validateDictType(dict.getDictType());
        }
        if (StringUtils.hasText(dict.getStatus())) {
            validateStatus(dict.getStatus());
        }
        SysDict patch = new SysDict();
        patch.setId(id);
        if (StringUtils.hasText(dict.getDictName()))  patch.setDictName(dict.getDictName());
        if (StringUtils.hasText(dict.getDictCode()))  patch.setDictCode(dict.getDictCode());
        if (StringUtils.hasText(dict.getDictType()))  patch.setDictType(dict.getDictType());
        if (dict.getRemark() != null)                 patch.setRemark(dict.getRemark());
        if (StringUtils.hasText(dict.getStatus()))    patch.setStatus(dict.getStatus());
        sysDictMapper.updateById(patch);
        // dict_code 变更时级联更新子表冗余字段
        if (dictCodeChanged) {
            sysDictItemMapper.update(
                    new LambdaUpdateWrapper<SysDictItem>()
                            .eq(SysDictItem::getDictId, id)
                            .set(SysDictItem::getDictCode, dict.getDictCode()));
            log.info("级联更新字典项 dictCode: dictId={}, newCode={}", id, dict.getDictCode());
        }
        log.info("更新字典: dictId={}", id);
        return sysDictMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDict(String id) {
        SysDict dict = requireDictExists(id);
        sysDictMapper.deleteById(id);
        sysDictItemMapper.delete(
                new LambdaQueryWrapper<SysDictItem>().eq(SysDictItem::getDictId, dict.getId()));
        log.info("删除字典: dictId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDictItem createDictItem(String code, SysDictItem item) {
        SysDict dict = requireDictByCode(code);
        SysDictItem entity = new SysDictItem();
        entity.setDictId(dict.getId());
        entity.setDictCode(dict.getDictCode());
        entity.setItemLabel(item.getItemLabel());
        entity.setItemValue(item.getItemValue());
        entity.setDescription(item.getDescription());
        entity.setSortOrder(item.getSortOrder() != null ? item.getSortOrder() : 0);
        entity.setExtra(item.getExtra());
        if (StringUtils.hasText(item.getStatus())) {
            validateStatus(item.getStatus());
        }
        entity.setStatus(StringUtils.hasText(item.getStatus()) ? item.getStatus() : DEFAULT_STATUS);
        sysDictItemMapper.insert(entity);
        log.info("创建字典项: itemId={}, dictCode={}", entity.getId(), code);
        return sysDictItemMapper.selectById(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysDictItem updateDictItem(String id, SysDictItem item) {
        requireDictItemExists(id);
        SysDictItem patch = new SysDictItem();
        patch.setId(id);
        if (StringUtils.hasText(item.getItemLabel()))  patch.setItemLabel(item.getItemLabel());
        if (StringUtils.hasText(item.getItemValue()))  patch.setItemValue(item.getItemValue());
        if (item.getDescription() != null)             patch.setDescription(item.getDescription());
        if (item.getSortOrder() != null)               patch.setSortOrder(item.getSortOrder());
        if (item.getExtra() != null)                   patch.setExtra(item.getExtra());
        if (StringUtils.hasText(item.getStatus()))     patch.setStatus(item.getStatus());
        if (StringUtils.hasText(item.getStatus()))     validateStatus(item.getStatus());
        sysDictItemMapper.updateById(patch);
        log.info("更新字典项: itemId={}", id);
        return sysDictItemMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDictItem(String id) {
        requireDictItemExists(id);
        sysDictItemMapper.deleteById(id);
        log.info("删除字典项: itemId={}", id);
    }

    private SysDict requireDictExists(String id) {
        SysDict dict = sysDictMapper.selectById(id);
        if (dict == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "字典不存在");
        }
        return dict;
    }

    private SysDict requireDictByCode(String code) {
        SysDict dict = sysDictMapper.selectOne(
                new LambdaQueryWrapper<SysDict>().eq(SysDict::getDictCode, code));
        if (dict == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "字典不存在: " + code);
        }
        return dict;
    }

    private void requireDictItemExists(String id) {
        if (sysDictItemMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "字典项不存在");
        }
    }

    private boolean existsByDictCode(String dictCode, String excludeId) {
        LambdaQueryWrapper<SysDict> wrapper = new LambdaQueryWrapper<SysDict>()
                .eq(SysDict::getDictCode, dictCode);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(SysDict::getId, excludeId);
        }
        return sysDictMapper.selectCount(wrapper) > 0;
    }

    private void validateDictType(String dictType) {
        if (!"biz".equals(dictType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "字典类型仅支持 biz");
        }
    }

    private void validateStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "状态仅支持 active 或 inactive");
        }
    }
}

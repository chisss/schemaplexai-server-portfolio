package com.schemaplexai.service.evaluation.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.EvalDatasetItemMapper;
import com.schemaplexai.dao.mapper.EvalDatasetMapper;
import com.schemaplexai.model.converter.EvaluationConverter;
import com.schemaplexai.model.dto.evaluation.EvalDatasetCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetItemRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetItemSaveRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetUpdateRequest;
import com.schemaplexai.model.entity.EvalDataset;
import com.schemaplexai.model.entity.EvalDatasetItem;
import com.schemaplexai.model.vo.evaluation.EvalDatasetVO;
import com.schemaplexai.service.evaluation.EvalDatasetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 评估数据集服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalDatasetServiceImpl implements EvalDatasetService {

    private final EvalDatasetMapper datasetMapper;
    private final EvalDatasetItemMapper itemMapper;
    private final EvaluationConverter evaluationConverter;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EvalDatasetVO create(EvalDatasetCreateRequest request) {
        EvalDataset dataset = new EvalDataset();
        dataset.setTenantId(SecurityUtil.getCurrentTenantId());
        dataset.setName(request.getName());
        dataset.setDescription(request.getDescription());
        datasetMapper.insert(dataset);
        if (!CollectionUtils.isEmpty(request.getItems())) {
            replaceItems(dataset.getId(), request.getItems());
        }
        log.info("创建评估数据集成功: datasetId={}, name={}", dataset.getId(), dataset.getName());
        return getById(dataset.getId());
    }

    @Override
    public PageResult<EvalDatasetVO> page(Integer page, Integer size, String keyword) {
        Page<EvalDataset> queryPage = new Page<>(page == null ? 1 : page, size == null ? 20 : size);
        LambdaQueryWrapper<EvalDataset> wrapper = new LambdaQueryWrapper<>();
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(EvalDataset::getTenantId, tenantId);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(EvalDataset::getName, keyword).or().like(EvalDataset::getDescription, keyword));
        }
        wrapper.orderByDesc(EvalDataset::getCreatedAt);
        Page<EvalDataset> result = datasetMapper.selectPage(queryPage, wrapper);
        List<EvalDatasetVO> records = result.getRecords().stream()
                .map(this::toVOWithItemCount)
                .collect(Collectors.toList());
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public EvalDatasetVO getById(String id) {
        EvalDataset dataset = requireDataset(id);
        EvalDatasetVO vo = toVOWithItemCount(dataset);
        List<EvalDatasetItem> items = itemMapper.selectList(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, id)
                .orderByAsc(EvalDatasetItem::getSortOrder)
                .orderByAsc(EvalDatasetItem::getCreatedAt));
        vo.setItems(evaluationConverter.toDatasetItemVOList(items));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EvalDatasetVO update(String id, EvalDatasetUpdateRequest request) {
        EvalDataset dataset = requireDataset(id);
        if (StringUtils.hasText(request.getName())) {
            dataset.setName(request.getName());
        }
        if (request.getDescription() != null) {
            dataset.setDescription(request.getDescription());
        }
        datasetMapper.updateById(dataset);
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public EvalDatasetVO saveItems(String id, EvalDatasetItemSaveRequest request) {
        requireDataset(id);
        replaceItems(id, request.getItems());
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireDataset(id);
        itemMapper.delete(new LambdaQueryWrapper<EvalDatasetItem>().eq(EvalDatasetItem::getDatasetId, id));
        datasetMapper.deleteById(id);
        log.info("删除评估数据集成功: datasetId={}", id);
    }

    private EvalDatasetVO toVOWithItemCount(EvalDataset dataset) {
        EvalDatasetVO vo = evaluationConverter.toDatasetVO(dataset);
        Long count = itemMapper.selectCount(new LambdaQueryWrapper<EvalDatasetItem>()
                .eq(EvalDatasetItem::getDatasetId, dataset.getId()));
        vo.setItemCount(count == null ? 0 : count.intValue());
        if (vo.getItems() == null) {
            vo.setItems(new ArrayList<>());
        }
        return vo;
    }

    private void replaceItems(String datasetId, List<EvalDatasetItemRequest> requests) {
        itemMapper.delete(new LambdaQueryWrapper<EvalDatasetItem>().eq(EvalDatasetItem::getDatasetId, datasetId));
        if (CollectionUtils.isEmpty(requests)) {
            return;
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        for (int i = 0; i < requests.size(); i++) {
            EvalDatasetItemRequest request = requests.get(i);
            EvalDatasetItem item = new EvalDatasetItem();
            item.setTenantId(tenantId);
            item.setDatasetId(datasetId);
            item.setInputText(request.getInputText());
            item.setExpectedOutput(request.getExpectedOutput());
            item.setMetadata(request.getMetadata());
            item.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : i);
            itemMapper.insert(item);
        }
    }

    private EvalDataset requireDataset(String id) {
        EvalDataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "评估数据集不存在");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId) && !tenantId.equals(dataset.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return dataset;
    }
}

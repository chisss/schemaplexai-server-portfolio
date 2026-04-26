package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.EvalDataset;
import com.schemaplexai.model.entity.EvalDatasetItem;
import com.schemaplexai.model.entity.EvalTask;
import com.schemaplexai.model.vo.evaluation.EvalDatasetItemVO;
import com.schemaplexai.model.vo.evaluation.EvalDatasetVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 评估模块转换器
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EvaluationConverter {

    EvalDatasetVO toDatasetVO(EvalDataset entity);

    List<EvalDatasetVO> toDatasetVOList(List<EvalDataset> entities);

    EvalDatasetItemVO toDatasetItemVO(EvalDatasetItem entity);

    List<EvalDatasetItemVO> toDatasetItemVOList(List<EvalDatasetItem> entities);

    EvalTaskVO toTaskVO(EvalTask entity);
}

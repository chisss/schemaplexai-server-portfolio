package com.schemaplexai.model.converter;

import com.schemaplexai.model.dto.cost.BudgetCreateRequest;
import com.schemaplexai.model.entity.Budget;
import com.schemaplexai.model.vo.cost.BudgetVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import java.util.List;

/**
 * 预算转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface BudgetConverter {

    BudgetVO toVO(Budget entity);
    List<BudgetVO> toVOList(List<Budget> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "status", constant = "active")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deleted", ignore = true)
    Budget fromCreateRequest(BudgetCreateRequest request);
}

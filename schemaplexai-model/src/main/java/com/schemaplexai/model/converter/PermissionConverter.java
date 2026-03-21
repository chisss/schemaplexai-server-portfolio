package com.schemaplexai.model.converter;

import com.schemaplexai.model.entity.Permission;
import com.schemaplexai.model.vo.system.PermissionVO;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

/**
 * 权限实体转换器
 */
@Mapper(componentModel = "spring", unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface PermissionConverter {

    PermissionVO toVO(Permission permission);

    List<PermissionVO> toVOList(List<Permission> permissions);
}

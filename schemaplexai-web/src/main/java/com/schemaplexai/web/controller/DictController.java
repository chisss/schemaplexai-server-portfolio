package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.entity.SysDict;
import com.schemaplexai.model.entity.SysDictItem;
import com.schemaplexai.model.vo.system.ConnectivityTestResultVO;
import com.schemaplexai.service.config.DictService;
import com.schemaplexai.service.config.SystemConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统字典管理控制器
 */
@Validated
@RestController
@RequestMapping("/system")
@RequiredArgsConstructor
@Tag(name = "系统字典管理")
public class DictController {

    private final DictService dictService;
    private final SystemConfigService systemConfigService;

    @GetMapping("/dicts")
    @Operation(summary = "查询字典列表（可按 type 过滤）")
    public R<List<SysDict>> listDicts(@RequestParam(required = false) String type) {
        return R.ok(dictService.listDicts(type));
    }

    @GetMapping("/dicts/{code}/items")
    @Operation(summary = "按字典编码查询字典项")
    public R<List<SysDictItem>> listDictItems(@PathVariable String code) {
        return R.ok(dictService.listItemsByDictCode(code));
    }

    @PostMapping("/dicts")
    @Operation(summary = "创建字典")
    public R<SysDict> createDict(@Valid @RequestBody DictCreateReq req) {
        SysDict entity = new SysDict();
        entity.setDictName(req.getDictName());
        entity.setDictCode(req.getDictCode());
        entity.setDictType(req.getDictType());
        entity.setRemark(req.getRemark());
        entity.setStatus(req.getStatus());
        return R.ok(dictService.createDict(entity));
    }

    @PutMapping("/dicts/{id}")
    @Operation(summary = "更新字典")
    public R<SysDict> updateDict(@PathVariable String id, @RequestBody DictUpdateReq req) {
        SysDict entity = new SysDict();
        entity.setDictName(req.getDictName());
        entity.setDictCode(req.getDictCode());
        entity.setDictType(req.getDictType());
        entity.setRemark(req.getRemark());
        entity.setStatus(req.getStatus());
        return R.ok(dictService.updateDict(id, entity));
    }

    @DeleteMapping("/dicts/{id}")
    @Operation(summary = "删除字典")
    public R<Void> deleteDict(@PathVariable String id) {
        dictService.deleteDict(id);
        return R.ok();
    }

    @PostMapping("/dicts/{code}/items")
    @Operation(summary = "创建字典项")
    public R<SysDictItem> createDictItem(@PathVariable String code,
                                          @Valid @RequestBody DictItemCreateReq req) {
        SysDictItem entity = buildDictItemFromReq(req.getItemLabel(), req.getItemValue(),
                req.getDescription(), req.getSortOrder(), req.getExtra(), req.getStatus());
        return R.ok(dictService.createDictItem(code, entity));
    }

    @PutMapping("/dicts/items/{id}")
    @Operation(summary = "更新字典项")
    public R<SysDictItem> updateDictItem(@PathVariable String id,
                                          @RequestBody DictItemUpdateReq req) {
        SysDictItem entity = buildDictItemFromReq(req.getItemLabel(), req.getItemValue(),
                req.getDescription(), req.getSortOrder(), req.getExtra(), req.getStatus());
        return R.ok(dictService.updateDictItem(id, entity));
    }

    @DeleteMapping("/dicts/items/{id}")
    @Operation(summary = "删除字典项")
    public R<Void> deleteDictItem(@PathVariable String id) {
        dictService.deleteDictItem(id);
        return R.ok();
    }

    /** 连通性测试别名（与 POST /models/{id}/test-connectivity 等效） */
    @GetMapping("/models/{id}/connectivity-test")
    @Operation(summary = "测试 AI 模型连通性（GET 别名）")
    public R<ConnectivityTestResultVO> connectivityTestGet(@PathVariable String id) {
        return R.ok(systemConfigService.testConnectivity(id));
    }

    private SysDictItem buildDictItemFromReq(String itemLabel, String itemValue,
                                              String description, Integer sortOrder,
                                              Map<String, Object> extra, String status) {
        SysDictItem entity = new SysDictItem();
        entity.setItemLabel(itemLabel);
        entity.setItemValue(itemValue);
        entity.setDescription(description);
        entity.setSortOrder(sortOrder);
        entity.setExtra(extra);
        entity.setStatus(status);
        return entity;
    }

    // ==================== Inner DTO ====================

    @Data
    public static class DictCreateReq {
        @NotBlank(message = "字典名称不能为空")
        private String dictName;
        @NotBlank(message = "字典编码不能为空")
        private String dictCode;
        @NotBlank(message = "字典类型不能为空")
        private String dictType;
        private String remark;
        private String status;
    }

    @Data
    public static class DictUpdateReq {
        private String dictName;
        private String dictCode;
        private String dictType;
        private String remark;
        private String status;
    }

    @Data
    public static class DictItemCreateReq {
        @NotBlank(message = "字典项标签不能为空")
        private String itemLabel;
        @NotBlank(message = "字典项值不能为空")
        private String itemValue;
        private String description;
        private Integer sortOrder;
        private Map<String, Object> extra;
        private String status;
    }

    @Data
    public static class DictItemUpdateReq {
        private String itemLabel;
        private String itemValue;
        private String description;
        private Integer sortOrder;
        private Map<String, Object> extra;
        private String status;
    }
}

package com.schemaplexai.service.config;

import com.schemaplexai.model.entity.SysDict;
import com.schemaplexai.model.entity.SysDictItem;

import java.util.List;

/**
 * 系统字典管理服务接口
 */
public interface DictService {

    List<SysDict> listDicts(String type);

    List<SysDictItem> listItemsByDictCode(String code);

    SysDict createDict(SysDict dict);

    SysDict updateDict(String id, SysDict dict);

    void deleteDict(String id);

    SysDictItem createDictItem(String code, SysDictItem item);

    SysDictItem updateDictItem(String id, SysDictItem item);

    void deleteDictItem(String id);
}

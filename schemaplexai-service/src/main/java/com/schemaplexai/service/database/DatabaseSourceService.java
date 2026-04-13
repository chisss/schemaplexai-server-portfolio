package com.schemaplexai.service.database;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceQueryRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceSaveRequest;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.model.vo.database.DatabaseSourceTestVO;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;

/**
 * 数据库数据源服务
 */
public interface DatabaseSourceService {

    /**
     * 分页查询数据库数据源
     */
    PageResult<DatabaseSourceVO> page(DatabaseSourceQueryRequest request);

    /**
     * 获取详情
     */
    DatabaseSourceVO getById(String id);

    /**
     * 创建数据源
     */
    DatabaseSourceVO create(DatabaseSourceSaveRequest request);

    /**
     * 更新数据源
     */
    DatabaseSourceVO update(String id, DatabaseSourceSaveRequest request);

    /**
     * 删除数据源
     */
    void delete(String id);

    /**
     * 测试草稿数据源
     */
    DatabaseSourceTestVO testDraft(DatabaseSourceSaveRequest request);

    /**
     * 测试已保存数据源
     */
    DatabaseSourceTestVO test(String id);

    /**
     * 执行查询
     */
    DatabaseQueryResultVO executeQuery(String id, DatabaseQueryExecuteRequest request);
}

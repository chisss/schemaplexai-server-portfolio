package com.schemaplexai.service.spec;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.dto.spec.SpecDiffRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentSubmitRequest;
import com.schemaplexai.model.dto.spec.SpecQueryRequest;
import com.schemaplexai.model.dto.spec.SpecUpdateRequest;
import com.schemaplexai.model.dto.spec.SpecWorkflowStartRequest;
import com.schemaplexai.model.vo.spec.SpecDiffVO;
import com.schemaplexai.model.vo.spec.SpecDocumentVO;
import com.schemaplexai.model.vo.spec.SpecWorkbenchVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.model.vo.spec.SpecVersionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;

import java.util.List;

/**
 * Spec管理服务接口
 */
public interface SpecService {

    /** 分页查询Spec列表 */
    PageResult<SpecVO> listSpecs(SpecQueryRequest request);

    /** 获取Spec详情（含文档） */
    SpecVO getSpecById(String id);

    /** 创建Spec */
    SpecVO createSpec(SpecCreateRequest request);

    /** 更新Spec基本信息 */
    SpecVO updateSpec(String id, SpecUpdateRequest request);

    /** 填写原始需求并启动工作流 */
    SpecVO startWorkflow(String id, SpecWorkflowStartRequest request);

    /** 删除Spec */
    void deleteSpec(String id);

    /** 提交审批 */
    void submitForReview(String id, String docType);

    /** 审批通过 */
    void approve(String id);

    /** 审批驳回 */
    void reject(String id);

    /** 获取Spec指定类型文档 */
    SpecDocumentVO getDocument(String specId, String docType);

    /** 获取工作流工作台聚合视图 */
    SpecWorkbenchVO getWorkbench(String specId);

    /** 保存/更新Spec文档（自动创建版本快照） */
    SpecDocumentVO saveDocument(String specId, String docType, SpecDocumentRequest request);

    /** 保存工作台文档节点草稿 */
    SpecDocumentVO saveWorkbenchDocument(String specId, String nodeId, SpecDocumentRequest request);

    /** 提交工作台文档节点并推进工作流 */
    SpecDocumentVO submitWorkbenchDocument(String specId, String nodeId, SpecDocumentSubmitRequest request);

    /** 获取文档版本历史 */
    List<SpecVersionVO> getVersionHistory(String specId, String docType);

    /** 获取特定版本详情 */
    SpecVersionVO getVersionById(String specId, String versionId);

    /** 版本Diff对比 */
    SpecDiffVO diffVersions(String specId, SpecDiffRequest request);

    /** 获取Spec绑定的工作流追踪信息（最新实例） */
    WorkflowInstanceVO getWorkflowTracking(String specId);
}

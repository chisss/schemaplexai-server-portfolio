package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityAuditEventQueryRequest;
import com.schemaplexai.model.dto.security.SecurityBindingSaveRequest;
import com.schemaplexai.model.dto.security.SecurityIncidentActionRequest;
import com.schemaplexai.model.dto.security.SecurityIncidentQueryRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyQueryRequest;
import com.schemaplexai.model.dto.security.SecurityPolicySaveRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyToggleRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackQueryRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackToggleRequest;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityAuditEventVO;
import com.schemaplexai.model.vo.security.SecurityBindingVO;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentActionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentVO;
import com.schemaplexai.model.vo.security.SecurityOverviewVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVersionVO;
import com.schemaplexai.model.vo.security.SecurityRuleItemVO;
import com.schemaplexai.model.vo.security.SecurityRulePackVO;
import com.schemaplexai.model.vo.security.SecurityTargetOptionsVO;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityComplianceService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.service.security.SecurityRulePackService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.schemaplexai.web.util.RequestContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

/**
 * 安全合规控制器
 */
@RestController
@RequestMapping("/security-compliance")
@RequiredArgsConstructor
@Tag(name = "安全合规管理")
public class SecurityComplianceController {

    private final SecurityComplianceService securityComplianceService;
    private final SecurityAuditEventService securityAuditEventService;
    private final SecurityIncidentService securityIncidentService;
    private final SecurityRulePackService securityRulePackService;
    private final SecurityRuntimeGuardService securityRuntimeGuardService;

    @GetMapping("/overview")
    @Operation(summary = "获取安全合规总览")
    public R<SecurityOverviewVO> getOverview() {
        return R.ok(securityComplianceService.getOverview());
    }

    @GetMapping("/targets/options")
    @Operation(summary = "获取安全策略目标选项")
    public R<SecurityTargetOptionsVO> listTargetOptions(@RequestParam(required = false) String scope) {
        return R.ok(securityComplianceService.listTargetOptions(scope));
    }

    @GetMapping("/policies")
    @Operation(summary = "分页查询安全策略")
    public R<PageResult<SecurityPolicyVO>> pagePolicies(SecurityPolicyQueryRequest request) {
        return R.ok(securityComplianceService.pagePolicies(request));
    }

    @GetMapping("/policies/{id}")
    @Operation(summary = "获取安全策略详情")
    public R<SecurityPolicyVO> getPolicy(@PathVariable String id) {
        return R.ok(securityComplianceService.getPolicyById(id));
    }

    @GetMapping("/policies/{id}/versions")
    @Operation(summary = "获取安全策略版本列表")
    public R<List<SecurityPolicyVersionVO>> listPolicyVersions(@PathVariable String id) {
        return R.ok(securityComplianceService.listPolicyVersions(id));
    }

    @GetMapping("/policies/{id}/bindings")
    @Operation(summary = "获取安全策略绑定关系")
    public R<List<SecurityBindingVO>> listPolicyBindings(@PathVariable String id) {
        return R.ok(securityComplianceService.listPolicyBindings(id));
    }

    @PostMapping("/policies/{id}/bindings")
    @Operation(summary = "保存安全策略绑定关系")
    public R<List<SecurityBindingVO>> savePolicyBindings(@PathVariable String id,
                                                         @RequestBody SecurityBindingSaveRequest request,
                                                         HttpServletRequest httpRequest) {
        return R.ok(securityComplianceService.savePolicyBindings(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/policies")
    @Operation(summary = "创建安全策略")
    public R<SecurityPolicyVO> createPolicy(@Valid @RequestBody SecurityPolicySaveRequest request,
                                            HttpServletRequest httpRequest) {
        return R.ok(securityComplianceService.createPolicy(request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PutMapping("/policies/{id}")
    @Operation(summary = "更新安全策略")
    public R<SecurityPolicyVO> updatePolicy(@PathVariable String id,
                                            @Valid @RequestBody SecurityPolicySaveRequest request,
                                            HttpServletRequest httpRequest) {
        return R.ok(securityComplianceService.updatePolicy(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/policies/{id}/publish")
    @Operation(summary = "发布安全策略")
    public R<SecurityPolicyVO> publishPolicy(@PathVariable String id, HttpServletRequest httpRequest) {
        return R.ok(securityComplianceService.publishPolicy(id, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/policies/{id}/toggle")
    @Operation(summary = "切换安全策略状态")
    public R<SecurityPolicyVO> togglePolicy(@PathVariable String id,
                                            @Valid @RequestBody SecurityPolicyToggleRequest request,
                                            HttpServletRequest httpRequest) {
        return R.ok(securityComplianceService.togglePolicy(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @DeleteMapping("/policies/{id}")
    @Operation(summary = "删除安全策略")
    public R<Void> deletePolicy(@PathVariable String id, HttpServletRequest httpRequest) {
        securityComplianceService.deletePolicy(id, RequestContextUtils.buildSecurityAuditContext(httpRequest));
        return R.ok();
    }

    @GetMapping("/audit-events")
    @Operation(summary = "分页查询安全审计事件")
    public R<PageResult<SecurityAuditEventVO>> pageAuditEvents(SecurityAuditEventQueryRequest request) {
        return R.ok(securityAuditEventService.page(request));
    }

    @GetMapping("/audit-events/{id}")
    @Operation(summary = "获取安全审计事件详情")
    public R<SecurityAuditEventVO> getAuditEvent(@PathVariable String id) {
        return R.ok(securityAuditEventService.getById(id));
    }

    @GetMapping("/audit-events/trace/{traceId}")
    @Operation(summary = "按链路ID查询安全审计事件")
    public R<List<SecurityAuditEventVO>> listAuditEventsByTrace(@PathVariable String traceId) {
        return R.ok(securityAuditEventService.listByTraceId(traceId));
    }

    @GetMapping("/incidents")
    @Operation(summary = "分页查询安全事件")
    public R<PageResult<SecurityIncidentVO>> pageIncidents(SecurityIncidentQueryRequest request) {
        return R.ok(securityIncidentService.page(request));
    }

    @GetMapping("/incidents/{id}")
    @Operation(summary = "获取安全事件详情")
    public R<SecurityIncidentVO> getIncident(@PathVariable String id) {
        return R.ok(securityIncidentService.getById(id));
    }

    @GetMapping("/incidents/{id}/actions")
    @Operation(summary = "获取安全事件处置记录")
    public R<List<SecurityIncidentActionVO>> listIncidentActions(@PathVariable String id) {
        return R.ok(securityIncidentService.listActions(id));
    }

    @PostMapping("/incidents/{id}/assign")
    @Operation(summary = "分派安全事件")
    public R<SecurityIncidentVO> assignIncident(@PathVariable String id,
                                                @Valid @RequestBody SecurityIncidentActionRequest request,
                                                HttpServletRequest httpRequest) {
        return R.ok(securityIncidentService.assign(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/incidents/{id}/resolve")
    @Operation(summary = "解决安全事件")
    public R<SecurityIncidentVO> resolveIncident(@PathVariable String id,
                                                 @Valid @RequestBody SecurityIncidentActionRequest request,
                                                 HttpServletRequest httpRequest) {
        return R.ok(securityIncidentService.resolve(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/incidents/{id}/ignore")
    @Operation(summary = "忽略安全事件")
    public R<SecurityIncidentVO> ignoreIncident(@PathVariable String id,
                                                @Valid @RequestBody SecurityIncidentActionRequest request,
                                                HttpServletRequest httpRequest) {
        return R.ok(securityIncidentService.ignore(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/incidents/{id}/escalate")
    @Operation(summary = "升级安全事件")
    public R<SecurityIncidentVO> escalateIncident(@PathVariable String id,
                                                  @Valid @RequestBody SecurityIncidentActionRequest request,
                                                  HttpServletRequest httpRequest) {
        return R.ok(securityIncidentService.escalate(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/incidents/{id}/resume-resource")
    @Operation(summary = "恢复被安全暂停的资源")
    public R<SecurityIncidentVO> resumeIncidentResource(@PathVariable String id, HttpServletRequest httpRequest) {
        return R.ok(securityIncidentService.resumeResource(id, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @GetMapping("/rule-packs")
    @Operation(summary = "分页查询安全规则包")
    public R<PageResult<SecurityRulePackVO>> pageRulePacks(SecurityRulePackQueryRequest request) {
        return R.ok(securityRulePackService.page(request));
    }

    @GetMapping("/rule-packs/{id}")
    @Operation(summary = "获取安全规则包详情")
    public R<SecurityRulePackVO> getRulePack(@PathVariable String id) {
        return R.ok(securityRulePackService.getById(id));
    }

    @PostMapping("/rule-packs")
    @Operation(summary = "创建安全规则包")
    public R<SecurityRulePackVO> createRulePack(@Valid @RequestBody SecurityRulePackSaveRequest request,
                                                HttpServletRequest httpRequest) {
        return R.ok(securityRulePackService.create(request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PutMapping("/rule-packs/{id}")
    @Operation(summary = "更新安全规则包")
    public R<SecurityRulePackVO> updateRulePack(@PathVariable String id,
                                                @Valid @RequestBody SecurityRulePackSaveRequest request,
                                                HttpServletRequest httpRequest) {
        return R.ok(securityRulePackService.update(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/rule-packs/{id}/toggle")
    @Operation(summary = "切换安全规则包状态")
    public R<SecurityRulePackVO> toggleRulePack(@PathVariable String id,
                                                @Valid @RequestBody SecurityRulePackToggleRequest request,
                                                HttpServletRequest httpRequest) {
        return R.ok(securityRulePackService.toggle(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @GetMapping("/rule-packs/{id}/items")
    @Operation(summary = "获取安全规则项列表")
    public R<List<SecurityRuleItemVO>> listRuleItems(@PathVariable String id) {
        return R.ok(securityRulePackService.listItems(id));
    }

    @GetMapping("/rule-packs/{id}/bindings")
    @Operation(summary = "获取规则包绑定关系")
    public R<List<SecurityBindingVO>> listRulePackBindings(@PathVariable String id) {
        return R.ok(securityRulePackService.listBindings(id));
    }

    @PostMapping("/rule-packs/{id}/bind-tenants")
    @Operation(summary = "保存规则包绑定关系")
    public R<List<SecurityBindingVO>> bindRulePack(@PathVariable String id,
                                                   @RequestBody SecurityBindingSaveRequest request,
                                                   HttpServletRequest httpRequest) {
        return R.ok(securityRulePackService.saveBindings(id, request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @PostMapping("/checks/runtime")
    @Operation(summary = "执行安全运行时检查")
    public R<SecurityCheckDecisionVO> runtimeCheck(@RequestBody SecurityRuntimeCheckRequest request,
                                                   HttpServletRequest httpRequest) {
        return R.ok(securityRuntimeGuardService.evaluate(request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }
}

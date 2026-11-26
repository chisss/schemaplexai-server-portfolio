package com.schemaplexai.service.agent.ontology;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentOntologyBinding;
import com.schemaplexai.model.dto.agent.AgentOntologyBindingsRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.service.semantic.application.orchestration.OntologyConfigurationApplicationService;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.ontology.ReleasedOntologyConfiguration;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 管理 Solo Agent 可使用的已发布业务本体。 */
@Service
@RequiredArgsConstructor
public class AgentOntologyBindingService {

    static final String VIEW_PERMISSION = "semantic:model:view";
    private final AgentMapper agents;
    private final AgentOntologyBindingStore store;
    private final SemanticModelRepository models;
    private final OntologyConfigurationApplicationService releases;

    public Bindings get(String id) {
        String tenant = currentTenant();
        Agent agent = requireAgent(tenant, id, false);
        return read(tenant, agent.getId());
    }

    @Transactional
    public Bindings replace(String id, AgentOntologyBindingsRequest request) {
        String tenant = currentTenant();
        Agent agent = requireAgent(tenant, id, true);
        List<AgentOntologyBinding> bindings = request.bindings().stream().map(this::normalize).toList();
        if (bindings.stream().map(AgentOntologyBinding::modelId).distinct().count() != bindings.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "每个本体只能绑定一次");
        }
        for (AgentOntologyBinding binding : bindings) {
            release(tenant, binding);
        }
        if (!store.replaceBindings(tenant, agent.getId(), request.expectedRevision(), bindings)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT, "Agent 本体绑定已被更新，请重新加载");
        }
        return new Bindings(request.expectedRevision() + 1, bindings);
    }

    Bindings read(String tenant, String agentId) {
        return store.findBindings(tenant, agentId).orElseGet(() -> new Bindings(0, List.of()));
    }

    SemanticModel model(String tenant, String id) {
        return models.findByTenantAndId(tenant, id)
                .filter(model -> model.getStatus() == SemanticModelStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
    }

    ReleasedOntologyConfiguration release(String tenant, AgentOntologyBinding binding) {
        SemanticModel model = model(tenant, binding.modelId());
        String version = binding.versionId() == null ? model.getActiveVersionId() : binding.versionId();
        if (version == null) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "本体需要可用的已发布版本");
        }
        ReleasedOntologyConfiguration loaded = releases.getReleasedForContext(version);
        if (!tenant.equals(loaded.tenantId()) || !model.getId().equals(loaded.modelId())
                || !version.equals(loaded.versionId()) || !StringUtils.hasText(loaded.checksum())) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND);
        }
        return loaded;
    }

    static boolean supports(Agent agent) {
        return AgentTypeEnum.SOLO.getCode().equals(agent.getAgentType())
                && !Boolean.TRUE.equals(agent.getIsSystemAgent());
    }

    private Agent requireAgent(String tenant, String id, boolean lock) {
        var query = new LambdaQueryWrapper<Agent>().eq(Agent::getId, uuid(id)).eq(Agent::getTenantId, tenant);
        Agent agent = agents.selectOne(lock ? query.last("FOR UPDATE") : query);
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        if (!supports(agent)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前仅支持为用户创建的 Solo Agent 配置业务本体");
        }
        return agent;
    }

    private AgentOntologyBinding normalize(AgentOntologyBinding binding) {
        return new AgentOntologyBinding(uuid(binding.modelId()),
                binding.versionId() == null ? null : uuid(binding.versionId()));
    }

    private String uuid(String value) {
        try {
            return AgentOntologyBindingStore.uuid(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本体绑定标识格式有误");
        }
    }

    private String currentTenant() {
        String tenant = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenant)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return tenant;
    }

    public record Bindings(long revision, List<AgentOntologyBinding> bindings) { }
}

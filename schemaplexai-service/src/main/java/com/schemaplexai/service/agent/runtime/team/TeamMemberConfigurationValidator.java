package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.common.enums.TeamMemberRoleTypeEnum;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TeamMemberConfigurationValidator {

    private TeamMemberConfigurationValidator() {
    }

    public record MemberRef(String id, String roleName, String roleType, String dependsOn, String canReadPeers) {
    }

    public static List<String> validate(List<MemberRef> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        Map<String, MemberRef> byId = new LinkedHashMap<>();
        long advisorCount = 0;
        for (MemberRef member : members) {
            if (member == null) {
                continue;
            }
            if (TeamMemberRoleTypeEnum.ADVISOR.matches(member.roleType())) {
                advisorCount++;
            }
            if (!StringUtils.hasText(member.id())) {
                continue;
            }
            if (byId.putIfAbsent(member.id(), member) != null) {
                errors.add("成员 ID 重复: " + member.id());
            }
        }
        if (advisorCount > 1) {
            errors.add("最多只能配置一个 Advisor 角色");
        }
        for (MemberRef member : members) {
            if (member == null) {
                continue;
            }
            validateReferences(member, member.dependsOn(), "depends_on", byId, errors);
            validateReferences(member, member.canReadPeers(), "can_read_peers", byId, errors);
        }
        if (hasDependencyCycle(members, byId)) {
            errors.add("成员依赖声明存在环");
        }
        return errors.stream().distinct().toList();
    }

    private static void validateReferences(MemberRef member, String references, String field,
                                           Map<String, MemberRef> byId, List<String> errors) {
        for (String reference : parseIds(references)) {
            if (reference.equals(member.id())) {
                errors.add(displayName(member) + " 的 " + field + " 不能引用自身");
                continue;
            }
            MemberRef target = byId.get(reference);
            if (target == null) {
                errors.add(displayName(member) + " 的 " + field + " 引用了未知成员: " + reference);
                continue;
            }
            if (TeamMemberRoleTypeEnum.LEAD_AGENT.matches(target.roleType())
                    || TeamMemberRoleTypeEnum.ADVISOR.matches(target.roleType())) {
                errors.add(displayName(member) + " 的 " + field + " 只能引用普通贡献者: " + reference);
            }
        }
    }

    private static boolean hasDependencyCycle(List<MemberRef> members, Map<String, MemberRef> byId) {
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        for (MemberRef member : members) {
            if (member == null || !StringUtils.hasText(member.id())
                    || TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.roleType())
                    || TeamMemberRoleTypeEnum.ADVISOR.matches(member.roleType())) {
                continue;
            }
            Set<String> refs = parseIds(member.dependsOn());
            refs.removeIf(id -> {
                MemberRef target = byId.get(id);
                return target == null || TeamMemberRoleTypeEnum.LEAD_AGENT.matches(target.roleType())
                        || TeamMemberRoleTypeEnum.ADVISOR.matches(target.roleType());
            });
            dependencies.put(member.id(), refs);
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String memberId : dependencies.keySet()) {
            if (hasCycle(memberId, dependencies, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCycle(String memberId, Map<String, Set<String>> dependencies,
                                    Set<String> visiting, Set<String> visited) {
        if (visited.contains(memberId)) {
            return false;
        }
        if (!visiting.add(memberId)) {
            return true;
        }
        for (String dependency : dependencies.getOrDefault(memberId, Set.of())) {
            if (dependencies.containsKey(dependency) && hasCycle(dependency, dependencies, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(memberId);
        visited.add(memberId);
        return false;
    }

    private static Set<String> parseIds(String value) {
        if (!StringUtils.hasText(value)) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String displayName(MemberRef member) {
        return StringUtils.hasText(member.roleName()) ? member.roleName() : String.valueOf(member.id());
    }
}

package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.port.CompositeQueryModelPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class CompositeQueryPlanner {
    private static final Pattern LITERALS = Pattern.compile(
            "[0-9]+(?:[-./][0-9]+)*|“[^”]+”|\"[^\"]+\"");
    private static final Pattern MONTH = Pattern.compile("按\\s*(?:月度|月份|月)|每个?月|\\b(?:monthly|by month)\\b",
            Pattern.CASE_INSENSITIVE);
    private final CompositeQueryModelPort model;

    public enum Presentation { SEPARATE, ALIGN_MONTH, DISTINCT }

    public record Part(String question, String title) {}

    public record Plan(List<Part> parts, Presentation presentation) {}

    public Plan plan(String question, String context) {
        var original = new Plan(List.of(new Part(question, question)), Presentation.SEPARATE);
        var candidate = model.interpret(SecurityUtil.getCurrentTenantId(), question, context);
        if (candidate == null || candidate.parts() == null || candidate.parts().size() < 2
                || candidate.parts().size() > 4) {
            return original;
        }
        var seen = new HashSet<String>();
        var text = LITERALS.matcher(question).results().map(match -> match.group()).distinct().toList();
        for (var part : candidate.parts()) {
            if (part == null || part.question() == null || part.title() == null || part.question().isBlank()
                    || part.title().isBlank() || part.question().length() > 2000 || part.title().length() > 120
                    || !seen.add(part.question().strip())
                    || !question.contains(part.title().strip())
                    || !part.question().contains(part.title().strip())
                    || text.stream().anyMatch(literal -> !part.question().contains(literal))
                    || (MONTH.matcher(question).find() && !MONTH.matcher(part.question()).find())) {
                return original;
            }
        }
        var parts = candidate.parts().stream().map(part -> new Part(part.question().strip(), part.title().strip())).toList();
        var presentation = "ALIGN_MONTH".equals(candidate.presentation()) && MONTH.matcher(question).find()
                ? Presentation.ALIGN_MONTH : "DISTINCT".equals(candidate.presentation())
                    ? Presentation.DISTINCT : Presentation.SEPARATE;
        return new Plan(parts, presentation);
    }
}

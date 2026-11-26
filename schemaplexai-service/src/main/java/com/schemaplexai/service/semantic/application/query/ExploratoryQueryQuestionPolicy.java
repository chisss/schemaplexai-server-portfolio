package com.schemaplexai.service.semantic.application.query;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** 对常见显式查询限制做保守检查，候选缺少限制时要求重新描述。 */
public final class ExploratoryQueryQuestionPolicy {

    private static final int CASE_INSENSITIVE = Pattern.CASE_INSENSITIVE;
    private static final Pattern UNSUPPORTED = Pattern.compile(
            "[<>≤≥≠!]|不等于|不为|排除|除外|除了|包含|开头|结尾|介于|大于|小于|不少于|不超过"
                    + "|起止|期间|至今|截止|截至|最近|过去|去年|今年|上个?月|本月|上周|本周|本季度|上季度"
                    + "|今[日天]|昨[日天]|前天|明天|(?:近|过去)\\s*\\d+\\s*(?:天|周|个?月|年)"
                    + "|按\\s*(?:天|日|周|季|年)(?:度|分组|统计|汇总|\\s|$)"
                    + "|\\d{4}年\\s*(?:\\d{1,2}月)?|\\d{4}-\\d{2}-\\d{2}\\s*(?:至|到|~|～|—|–|\\bto\\b)"
                    + "|\\d{4}/\\d{1,2}/\\d{1,2}|排名|排行|名次|排序|升序|降序"
                    + "|(?:前|后)\\s*\\d+|(?:前|后|最高|最低|最大|最小)的?\\s*[一二两三四五六七八九十百\\d]+\\s*(?:个|条|名)"
                    + "|或者|任一|任意一个|\\d+\\s*%|==|跨表|联表|明细|去重"
                    + "|\\b(?:between|before|after|since|until|yesterday|today|sort|rank|order\\s+by|top|bottom"
                    + "|not|or|like|greater\\s+than|less\\s+than|limit|offset|join|distinct)\\b"
                    + "|\\b(?:last|past|next|this|previous)\\s+(?:\\d+\\s+)?(?:days?|weeks?|months?|quarters?|years?)\\b",
            CASE_INSENSITIVE);
    private static final Pattern FILTER_HINT = Pattern.compile(
            "仅看|只看|只要|只统计|仅统计|限于|筛选|过滤|满足|\\b(?:where|filter|only)\\b", CASE_INSENSITIVE);
    private static final Pattern EQUALITY = Pattern.compile(
            "(?<!=)=(?!=)|＝|等于|(?<![作因成认所行视名变实更尤])为(?!何|了|什|啥|例|止)"
                    + "|(?<=状态|类型|类别|地区|部门|渠道|标识)是(?!否|什|哪)|\\bequals?\\b",
            CASE_INSENSITIVE);
    private static final Pattern FILTER_CONJUNCTION = Pattern.compile(
            "并且|且|同时|\\band\\b", CASE_INSENSITIVE);
    private static final Pattern GROUPING = Pattern.compile(
            "分组|按[^，。？]+?(?:统计|汇总)|\\bgroup\\s+by\\b", CASE_INSENSITIVE);
    private static final Pattern NAMED_GROUP = Pattern.compile(
            "(?:按\\s*|\\bgroup\\s+by\\s+)([A-Za-z_][A-Za-z0-9_$]{0,62})(?![A-Za-z0-9_$])",
            CASE_INSENSITIVE);
    private static final Pattern DATE_LITERAL = Pattern.compile("(?<![0-9])\\d{4}-\\d{2}-\\d{2}(?![0-9])");
    private static final Pattern EXPLICIT_FIELD = Pattern.compile("([A-Za-z_][A-Za-z0-9_$]{0,62})\\s*$");
    private static final Pattern MONTH_GROUP = Pattern.compile(
            "按\\s*(?:月度|月份|月)|每\\s*(?:个)?月|\\b(?:by\\s+month|monthly)\\b", CASE_INSENSITIVE);

    private ExploratoryQueryQuestionPolicy() {
    }

    public static boolean supports(String question) {
        return !UNSUPPORTED.matcher(question).find();
    }

    public static boolean requestsMonthGrouping(String question) {
        return MONTH_GROUP.matcher(question).find();
    }

    public static boolean preservesConstraints(String question, List<String> dimensions, List<FilterEvidence> filters) {
        var equality = EQUALITY.matcher(question);
        var consumed = new boolean[filters.size()];
        while (equality.find()) {
            String expression = question.substring(equality.end());
            var explicit = EXPLICIT_FIELD.matcher(question.substring(0, equality.start()));
            String namedField = explicit.find() ? explicit.group(1) : null;
            if (!matchOne(filters, consumed, filter -> (namedField == null || namedField.equals(filter.fieldName()))
                    && filter.startsWithValue().test(expression))) {
                return false;
            }
        }

        var hint = FILTER_HINT.matcher(question);
        if (hint.find()) {
            String[] clauses = FILTER_CONJUNCTION.split(question.substring(hint.end()));
            var matched = new boolean[filters.size()];
            for (String clause : clauses) {
                if (!matchOne(filters, matched, filter -> filter.containsValue().test(clause))) {
                    return false;
                }
            }
        }

        var dates = DATE_LITERAL.matcher(question);
        while (dates.find()) {
            String literal = dates.group();
            if (filters.stream().noneMatch(filter -> literal.equals(filter.date()))) {
                return false;
            }
        }
        var grouping = NAMED_GROUP.matcher(question);
        while (grouping.find()) {
            if (!dimensions.contains(grouping.group(1))) {
                return false;
            }
        }
        return !dimensions.isEmpty() || !GROUPING.matcher(question).find();
    }

    private static boolean matchOne(List<FilterEvidence> filters, boolean[] matched, Predicate<FilterEvidence> accepts) {
        for (int index = 0; index < filters.size(); index++) {
            if (!matched[index] && accepts.test(filters.get(index))) {
                matched[index] = true;
                return true;
            }
        }
        return false;
    }

    public record FilterEvidence(String fieldName, Predicate<String> containsValue,
                                 Predicate<String> startsWithValue, String date) {
    }
}

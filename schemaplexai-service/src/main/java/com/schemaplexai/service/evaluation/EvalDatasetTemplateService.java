package com.schemaplexai.service.evaluation;

import com.schemaplexai.model.vo.evaluation.EvalDatasetTemplateVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评估数据集内置模板服务
 * <p>
 * 提供场景化数据集模板（RAG 知识问答等），供前端"从模板创建数据集"一键导入。
 * RAG 模板条目带 metadata.context（检索上下文），与 metadata 评估配置下放能力
 * （{@link EvalMetadataConfig}）配套形成完整 RAG 评估链路。
 * </p>
 */
@Service
public class EvalDatasetTemplateService {

    /**
     * 返回全部内置模板
     */
    public List<EvalDatasetTemplateVO> list() {
        return List.of(ragTemplate(), generalTemplate());
    }

    /**
     * RAG 知识问答模板：条目含检索上下文（context），覆盖
     * 单片段抽取、多片段综合、干扰信息抗性、上下文外拒答（幻觉控制）四类场景
     */
    private EvalDatasetTemplateVO ragTemplate() {
        EvalDatasetTemplateVO template = baseTemplate(
                "rag-qa", "RAG 知识问答评估", "RAG",
                "覆盖单片段抽取、多片段综合、干扰信息抗性与上下文外拒答四类场景；"
                        + "条目 metadata.context 注入检索上下文，评估模型的信息整合与幻觉控制能力。",
                80);
        template.setItems(List.of(
                item("""
根据公司《差旅报销管理办法》（2025 版）：
1. 一线城市（北上广深）住宿报销上限为每晚 600 元；
2. 二线城市住宿报销上限为每晚 450 元；
3. 员工出差期间餐补标准为每天 100 元；
4. 报销需在出差结束后 30 天内提交申请。"""
,
                        "去上海出差，每晚住宿最多能报销多少钱？",
                        "上海属于一线城市，按《差旅报销管理办法》住宿报销上限为每晚 600 元。"),
                item("""
产品 A：支持最大内存 64GB，处理器可选 8 核或 12 核；
产品 B：支持最大内存 32GB，处理器固定 8 核；
产品 C：支持最大内存 128GB，处理器固定 16 核，价格最高。"""
,
                        "产品 B 最大支持多少内存？处理器可以选配吗？",
                        "产品 B 最大支持 32GB 内存；处理器固定为 8 核，不可选配。"),
                item("""
【合同 SLA 条款】系统可用性不低于 99.9%，全年故障时间不超过 8.76 小时。
【故障响应承诺】P0 级故障 15 分钟内响应，2 小时内恢复；P1 级故障 1 小时内响应，8 小时内恢复。
【其他】甲方需在每月 5 日前支付上月服务费。"""
,
                        "如果系统发生 P0 级故障，服务商的响应和恢复时间承诺分别是什么？",
                        "P0 级故障承诺 15 分钟内响应、2 小时内恢复。"),
                item("""
【片段一】公司年会定于 12 月 20 日在杭州举行。
【片段二】年会晚宴提供自助餐，标准为每人 300 元；差旅费用按普通出差标准报销。
【干扰信息】有传言说年会改期到明年 1 月，但该消息未经确认。"""
,
                        "公司年会在哪里举行？晚宴餐标是多少？",
                        "年会于 12 月 20 日在杭州举行（改期传言未经确认，不予采信）；晚宴餐标为每人 300 元。"),
                item("""
【上下文】2025 年第三季度公司营收为 1.2 亿元，同比增长 15%。"""
,
                        "公司第四季度的利润是多少？",
                        "基于提供的上下文无法回答：上下文中只有第三季度营收数据，没有第四季度利润信息。应明确说明无法回答，不得编造数据。")
        ));
        return template;
    }

    /**
     * 通用问答模板：覆盖计算、格式约束、事实核对与逻辑推理
     */
    private EvalDatasetTemplateVO generalTemplate() {
        EvalDatasetTemplateVO template = baseTemplate(
                "general-qa", "通用问答评估", "通用",
                "覆盖计算、格式约束、事实核对与逻辑推理四类基础能力，不带检索上下文。",
                null);
        template.setItems(List.of(
                item(null, "一个西瓜 12 元，买 3 个打 8 折，总共需要多少钱？", "3 个西瓜原价 36 元，打 8 折后共 28.8 元。"),
                item(null, "请用 JSON 格式输出：姓名张三、年龄 30、职位工程师。", """
                        {
                          "name": "张三",
                          "age": 30,
                          "position": "工程师"
                        }"""
.trim()),
                item(null, "长江是世界第几长河？", "长江是世界第三长河（长度约 6300 公里，次于尼罗河与亚马逊河）。"),
                item(null, "所有猫都吃肉，小明家养的是猫，所以？", "小明家的猫吃肉。三段论推理：所有猫吃肉 + 小明家养的是猫 → 该猫吃肉。")
        ));
        return template;
    }

    private EvalDatasetTemplateVO baseTemplate(String id, String name, String category,
                                               String description, Integer suggestedThreshold) {
        EvalDatasetTemplateVO template = new EvalDatasetTemplateVO();
        template.setId(id);
        template.setName(name);
        template.setCategory(category);
        template.setDescription(description);
        template.setSuggestedPassScoreThreshold(suggestedThreshold);
        return template;
    }

    private EvalDatasetTemplateVO.TemplateItem item(String context, String inputText, String expectedOutput) {
        EvalDatasetTemplateVO.TemplateItem item = new EvalDatasetTemplateVO.TemplateItem();
        item.setInputText(inputText);
        item.setExpectedOutput(expectedOutput);
        if (context != null) {
            item.setMetadata(Map.of("context", context));
        }
        return item;
    }

    /**
     * 按 ID 查找模板（不存在返回 null）
     */
    public EvalDatasetTemplateVO findById(String id) {
        return list().stream().filter(template -> template.getId().equals(id)).findFirst().orElse(null);
    }

    /**
     * 测试辅助：校验模板条目数（RAG 模板 5 条 / 通用模板 4 条）
     */
    List<Integer> itemCountsForTest() {
        List<Integer> counts = new ArrayList<>();
        for (EvalDatasetTemplateVO template : list()) {
            counts.add(template.getItems().size());
        }
        return counts;
    }
}

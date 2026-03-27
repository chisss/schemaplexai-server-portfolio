-- =====================================================
-- SchemaPlexAI 行业模板：零售/电商
-- 使用场景: 商品描述优化、营销文案生成、客服回复、选品分析
-- =====================================================

-- =====================
-- 1. 企业角色
-- =====================
INSERT INTO sf_role (id, tenant_id, name, code, description, is_system, status, created_at, updated_at) VALUES
    (gen_random_uuid(), :tenant_id, '电商运营总监', 'ECOMMERCE_DIRECTOR', '统筹电商平台运营策略，管理GMV目标达成', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '商品运营专员', 'PRODUCT_OPERATOR', '负责商品上架、详情页优化、选品分析', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '营销策划师', 'MARKETING_PLANNER', '策划促销活动、撰写营销文案、管理内容矩阵', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '客服主管', 'CS_SUPERVISOR', '管理客服团队，处理售后问题，优化服务流程', false, 'active', NOW(), NOW()),
    (gen_random_uuid(), :tenant_id, '数据分析师', 'DATA_ANALYST', '分析销售数据、用户行为、转化漏斗，提供决策支持', false, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 2. 上下文
-- =====================
INSERT INTO sf_context (id, tenant_id, name, context_level, description, status, created_at, updated_at, deleted) VALUES
    ('ctx-retail-001-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '品牌调性与卖点库', 'global', '品牌定位、核心卖点、禁用词和目标客群描述', 'active', NOW(), NOW(), 0),
    ('ctx-retail-002-' || REPLACE(:tenant_id::text, '-', ''), :tenant_id,
     '营销活动素材库', 'global', '历史爆款文案、平台规则和促销活动模板', 'active', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

INSERT INTO sf_context_item (id, context_id, title, content, item_type, sort_order, status, created_at, updated_at) VALUES
    (gen_random_uuid(),
     'ctx-retail-001-' || REPLACE(:tenant_id::text, '-', ''),
     '品牌调性手册',
     E'# 品牌调性手册 v1.0\n\n## 品牌定位\n我们是一家专注于年轻女性服饰的快时尚电商品牌，目标客群为18-35岁都市女性，追求"时尚、平价、高品质"。\n\n## 核心卖点\n1. 性价比之王: 同款设计比大牌便宜60%\n2. 快速上新: 每周上新50+款式，紧跟潮流\n3. 质量可靠: 所有面料经过SGS认证，安全无害\n4. 尺码友好: XS-4XL全码段，微胖女生也能买到\n\n## 文案风格\n- 语气: 亲切活泼，像朋友推荐，不过度夸大\n- 用词: 简洁直接，多用数字和具体描述\n- 禁用词: 最、第一、全网最低（违反广告法）\n- 推荐用词: "超划算"、"性价比绝了"、"穿搭必备"\n\n## 目标客群画像\n- 年龄: 20-30岁\n- 职业: 职场新人/学生/自由职业\n- 关注点: 价格敏感但不愿放弃品质\n- 购物场景: 通勤穿搭、周末出游、节日礼物',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-retail-001-' || REPLACE(:tenant_id::text, '-', ''),
     '商品描述写作规范',
     E'# 商品描述写作规范\n\n## 标题结构（60字以内）\n[品牌] + [核心属性] + [商品名] + [适用场景/人群]\n示例: "2026春款 显瘦A字裙 波点印花连衣裙 通勤约会两穿"\n\n## 详情页结构\n1. 首屏大图: 模特实拍+产品特写（不少于3张）\n2. 卖点提炼: 3-5个核心卖点图文说明\n3. 材质工艺: 面料成分+克重+做工特点\n4. 尺码指引: 详细尺码表+身材推荐参考\n5. 真实评价: 精选买家秀（获得授权）\n6. 搭配建议: 上下装/配件搭配方案\n\n## 禁止行为\n- 模特身材过度修图（违反平台规则）\n- 使用"100%真品/正品"等绝对用语\n- 虚假销量/评论数据',
     'text', 2, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-retail-002-' || REPLACE(:tenant_id::text, '-', ''),
     '爆款文案模板库',
     E'# 爆款文案模板库\n\n## 短视频开场钩子（前3秒）\n- "这条裙子让我在聚会上被问了8次在哪买的！"\n- "终于找到了！显瘦20斤的神裙！"\n- "打工人穿搭｜100块以内的精致感"\n\n## 促销活动文案模板\n### 限时折扣\n"⚡限时48小时｜原价199，现价99！\n这款[商品名]已经卖出8000件，今天最后一天这个价！\n👉 点击购买，满200减30"\n\n### 新品首发\n"🆕刚到的宝贝，首发价只要XX元！\n限量500件，先到先得～\n❤️ 收藏关注，新品优先推送"\n\n### 用户晒单引导\n"宝贝们收到货了吗？\n欢迎晒出你的穿搭照，被选为买家秀送20元无门槛券！\n#品牌名穿搭 话题记得带上这个标签～"',
     'text', 1, 'active', NOW(), NOW()),
    (gen_random_uuid(),
     'ctx-retail-002-' || REPLACE(:tenant_id::text, '-', ''),
     '客服应答知识库',
     E'# 客服应答知识库\n\n## 常见问题快速回复\n\n### 发货时效\n"您好！我们工作日24小时内发货，节假日顺延1-2天。\n发货后快递一般3-5天到达，偏远地区7-10天，请耐心等待哦~"\n\n### 退换货政策\n"亲爱的顾客，我们支持7天无理由退换货！\n收到货后如有质量问题，请在24小时内联系我们，我们承担来回运费。\n如因个人原因退货，需保持商品全新未穿着状态，运费由买家承担。"\n\n### 尺码建议\n"建议您参考详情页的尺码表，按实际三围+5cm选择。\n如果您平时M码，建议选L码，我们的版型偏修身~\n不确定的话可以告诉我您的身高体重，我来帮您推荐！"\n\n### 投诉处理\n对话开头: "非常抱歉给您带来了不愉快的购物体验！"\n承诺处理: "我会立即为您处理，并在[时限]内给您回复"\n补偿方案: "作为补偿，我们为您发放20元无门槛优惠券"',
     'text', 2, 'active', NOW(), NOW())
ON CONFLICT DO NOTHING;

-- =====================
-- 3. Agent
-- =====================
INSERT INTO sf_agent (id, tenant_id, name, agent_type, description, ai_model, ai_model_type, status, max_concurrency, trigger_type, skills, agent_tag, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '商品文案优化师', 'solo',
     '基于品牌调性和卖点库，优化商品标题和详情描述，提升搜索排名和转化率',
     'DEFAULT_MODEL', 'model', 'active', 5, 'manual',
     '["copywriting","seo_optimization","product_description"]'::jsonb,
     'retail', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '营销文案生成器', 'solo',
     '根据活动类型和商品特点，生成适合各平台的营销文案（短视频脚本、推文、海报文字）',
     'DEFAULT_MODEL', 'model', 'active', 5, 'manual',
     '["marketing_copy","social_media","campaign_planning"]'::jsonb,
     'retail', NOW(), NOW(), 0),
    (gen_random_uuid(), :tenant_id, '智能客服助手', 'solo',
     '基于知识库自动回复常见客户问题，处理投诉、退换货、物流查询等标准化场景',
     'DEFAULT_MODEL', 'model', 'active', 10, 'event',
     '["customer_service","faq_answer","complaint_handling"]'::jsonb,
     'retail', NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- =====================
-- 4. 销售+营销工作流
-- =====================
INSERT INTO sf_workflow (id, tenant_id, name, description, status, trigger_type, trigger_config, node_config, created_at, updated_at, deleted) VALUES
    (gen_random_uuid(), :tenant_id, '新品上线全流程', '从新品录入到多平台上线的完整自动化流程',
     'active', 'manual', '{}'::jsonb,
     '{
       "nodes": [
         {"id": "start", "type": "start", "label": "新品录入", "position": {"x": 100, "y": 200}},
         {"id": "copy_optimize", "type": "agent", "label": "文案优化", "position": {"x": 350, "y": 200}, "config": {"agentRole": "商品文案优化师"}},
         {"id": "marketing", "type": "agent", "label": "营销内容生成", "position": {"x": 600, "y": 200}, "config": {"agentRole": "营销文案生成器"}},
         {"id": "end", "type": "end", "label": "发布上线", "position": {"x": 850, "y": 200}}
       ],
       "edges": [
         {"source": "start", "target": "copy_optimize"},
         {"source": "copy_optimize", "target": "marketing"},
         {"source": "marketing", "target": "end"}
       ]
     }'::jsonb,
     NOW(), NOW(), 0)
ON CONFLICT DO NOTHING;

-- 为 sf_ai_model 表增加推理强度支持字段
ALTER TABLE sf_ai_model ADD COLUMN IF NOT EXISTS supported_reasoning_efforts VARCHAR(100);

COMMENT ON COLUMN sf_ai_model.supported_reasoning_efforts IS '支持的推理强度等级，逗号分隔，如 low,medium,high';

-- 预设主流模型的推理强度支持
-- Claude 系列（支持 extended thinking）
UPDATE sf_ai_model SET supported_reasoning_efforts = 'low,medium,high'
WHERE provider IN ('claude', 'anthropic') AND supported_reasoning_efforts IS NULL;

-- OpenAI o 系列（支持 reasoning_effort）
UPDATE sf_ai_model SET supported_reasoning_efforts = 'low,medium,high'
WHERE provider = 'openai' AND model_id LIKE 'o%' AND supported_reasoning_efforts IS NULL;

-- Gemini 2.5 系列（支持 thinking config）
UPDATE sf_ai_model SET supported_reasoning_efforts = 'low,medium,high'
WHERE provider IN ('gemini', 'google') AND model_id LIKE 'gemini-2.5%' AND supported_reasoning_efforts IS NULL;

-- DeepSeek R1 系列（支持 reasoning）
UPDATE sf_ai_model SET supported_reasoning_efforts = 'low,medium,high'
WHERE provider = 'deepseek' AND model_id LIKE '%r1%' AND supported_reasoning_efforts IS NULL;

ALTER TABLE sf_ai_model
    ADD COLUMN IF NOT EXISTS protocol VARCHAR(20) DEFAULT 'openai',
    ADD COLUMN IF NOT EXISTS image_price DECIMAL(10, 6);

COMMENT ON COLUMN sf_ai_model.protocol IS '调用协议: openai, anthropic, gemini';
COMMENT ON COLUMN sf_ai_model.image_price IS '生图单张价格';

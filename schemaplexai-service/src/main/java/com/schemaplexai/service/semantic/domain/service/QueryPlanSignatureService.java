package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** 使用服务端密钥签发计划，防止客户端篡改物理字段和限制。 */
public final class QueryPlanSignatureService {

    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public QueryPlanSignatureService(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("query plan signing secret is required");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public SignedQueryPlan sign(QueryPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }
        return new SignedQueryPlan(plan, signature(plan));
    }

    public void verify(SignedQueryPlan signedPlan) {
        if (signedPlan == null || !MessageDigest.isEqual(
                signature(signedPlan.plan()).getBytes(StandardCharsets.UTF_8),
                signedPlan.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("query plan signature is invalid");
        }
    }

    private String signature(QueryPlan plan) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            String canonical = plan.semanticVersionId() + "|" + plan.sourceId() + "|"
                    + plan.databaseType() + "|" + plan.planHash() + "|" + plan.query().statement();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to sign query plan", exception);
        }
    }
}

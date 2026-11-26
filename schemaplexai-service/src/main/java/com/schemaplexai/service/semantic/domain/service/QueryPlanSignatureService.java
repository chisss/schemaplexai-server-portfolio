package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
            var fields = new ArrayList<>(List.of(Objects.toString(plan.semanticVersionId(), ""), plan.sourceId(),
                    plan.databaseType(), plan.planHash(), plan.query().language(), plan.query().statement(),
                    Boolean.toString(plan.structured()), Boolean.toString(plan.exploratory())));
            plan.query().parameters().forEach(parameter -> fields.addAll(
                    List.of(parameter.name(), parameter.value(), Boolean.toString(parameter.textValue()))));
            plan.query().columns().forEach(column -> fields.addAll(
                    List.of(column.role(), column.alias(), column.expression(), column.semanticIri())));
            plan.query().lineage().forEach(line -> fields.addAll(
                    List.of(line.semanticIri(), line.sourceId(), line.physicalObject(), line.physicalField())));
            if (plan.reference() != null) {
                var reference = plan.reference();
                fields.addAll(List.of("business-reference", reference.bindingKey(), reference.datasetId(),
                        reference.sourceId(), reference.physicalObject(), reference.versionId(), reference.releaseChecksum(),
                        reference.schemaSnapshotId(), reference.sourceFingerprint(), reference.measurePropertyId(),
                        reference.measureIri(), reference.aggregation(), reference.measureColumn(), reference.referenceView(),
                        reference.referenceColumn(), reference.tolerance().toPlainString(), reference.label(),
                        reference.sourceRevision()));
                appendComparisonInput(plan, fields);
                fields.add(Integer.toString(reference.dimensions().size()));
                reference.dimensions().forEach(dimension -> fields.addAll(List.of(
                        dimension.propertyId(), dimension.iri(), dimension.physicalField(), dimension.dataType(),
                        dimension.physicalType(), dimension.referenceColumn())));
                fields.add(Boolean.toString(reference.time() != null));
                if (reference.time() != null) {
                    fields.addAll(List.of(reference.time().propertyId(), reference.time().iri(), reference.time().physicalField(),
                            reference.time().dataType(), reference.time().physicalType(), reference.time().timeZone(),
                            reference.time().referenceColumn()));
                }
            }
            String canonical = fields.stream().map(field -> field.length() + ":" + field)
                    .collect(Collectors.joining());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to sign query plan", exception);
        }
    }

    private void appendComparisonInput(QueryPlan plan, List<String> fields) {
        fields.add(Integer.toString(plan.query().dimensions().size()));
        plan.query().dimensions().forEach(dimension -> {
            fields.addAll(List.of(dimension.iri(), dimension.alias(), Objects.toString(dimension.timeGrain(), ""),
                    dimension.physicalObject(), dimension.physicalField()));
            if (dimension.calendar() == null) {
                fields.add("");
                fields.add("");
            } else {
                fields.add(dimension.calendar().timeZone());
                fields.add(dimension.calendar().physicalType());
            }
        });
        var timeRange = plan.query().timeRange();
        fields.add(Boolean.toString(timeRange != null));
        if (timeRange != null) {
            fields.addAll(List.of(timeRange.fieldIri(), Objects.toString(timeRange.relativeDays(), ""),
                    Objects.toString(timeRange.from(), ""), Objects.toString(timeRange.to(), ""),
                    Objects.toString(timeRange.physicalObject(), ""), Objects.toString(timeRange.physicalField(), "")));
            if (timeRange.calendar() == null) {
                fields.add("");
                fields.add("");
            } else {
                fields.add(timeRange.calendar().timeZone());
                fields.add(timeRange.calendar().physicalType());
            }
        }
    }
}

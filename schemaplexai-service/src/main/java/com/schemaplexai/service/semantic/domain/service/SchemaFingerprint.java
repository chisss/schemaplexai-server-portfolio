package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaForeignKey;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaIndex;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 对规范化结构计算稳定 SHA-256，不包含样例值或凭据。 */
public final class SchemaFingerprint {

    public String calculate(DatabaseSchema schema) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, schema.databaseType(), schema.catalog(), schema.schema());
        schema.capabilities().forEach(value -> append(canonical, "cap", value));
        for (SchemaObject object : schema.objects()) {
            append(canonical, "object", object.namespace(), object.name(), object.type().name(), object.comment(),
                    object.engine(), object.partitionKey());
            object.primaryKey().forEach(value -> append(canonical, "pk", value));
            for (SchemaField field : object.fields()) {
                append(canonical, "field", field.name(), field.dataType(),
                        Boolean.toString(field.nullable()), field.comment());
                field.typeDistribution().forEach((type, count) ->
                        append(canonical, "distribution", type, String.valueOf(count)));
            }
            for (SchemaForeignKey foreignKey : object.foreignKeys()) {
                append(canonical, "fk", foreignKey.name(), foreignKey.referencedSchema(),
                        foreignKey.referencedObject(), String.join(",", foreignKey.columns()),
                        String.join(",", foreignKey.referencedColumns()));
            }
            for (SchemaIndex index : object.indexes()) {
                append(canonical, "index", index.name(), Boolean.toString(index.unique()),
                        index.type(), String.join(",", index.columns()));
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void append(StringBuilder target, String... values) {
        for (String value : values) {
            String normalized = value == null ? "" : value;
            target.append(normalized.length()).append(':').append(normalized).append('|');
        }
        target.append('\n');
    }
}

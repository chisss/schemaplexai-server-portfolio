package com.schemaplexai.service.database.credential;

import java.util.Map;

/**
 * 数据库凭据存取端口
 */
public interface DatabaseCredentialVault {

    String save(String secretRef, Map<String, Object> credential);

    Map<String, Object> resolve(String secretRef);

    void delete(String secretRef);
}

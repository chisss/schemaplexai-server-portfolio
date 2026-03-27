package com.schemaplexai.service.marketplace.ratelimit;

public interface PluginRateLimiter {
    void acquire(String tenantId, String actionKey);
}

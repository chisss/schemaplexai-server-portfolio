package com.schemaplexai.common.util;

import java.util.function.Supplier;

/**
 * 系统级租户操作上下文
 * <p>
 * 仅用于登录、初始化、系统任务等明确不依赖当前租户的内部流程。
 */
public final class SystemTenantContext {

    private SystemTenantContext() {}

    private static final ThreadLocal<Boolean> SYSTEM_OPERATION = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean isSystemOperation() {
        return Boolean.TRUE.equals(SYSTEM_OPERATION.get());
    }

    public static void setSystemOperation(boolean systemOperation) {
        SYSTEM_OPERATION.set(systemOperation);
    }

    public static <T> T callAsSystem(Supplier<T> supplier) {
        boolean previous = isSystemOperation();
        setSystemOperation(true);
        try {
            return supplier.get();
        } finally {
            setSystemOperation(previous);
        }
    }

    public static void runAsSystem(Runnable runnable) {
        boolean previous = isSystemOperation();
        setSystemOperation(true);
        try {
            runnable.run();
        } finally {
            setSystemOperation(previous);
        }
    }

    public static void clear() {
        SYSTEM_OPERATION.remove();
    }
}

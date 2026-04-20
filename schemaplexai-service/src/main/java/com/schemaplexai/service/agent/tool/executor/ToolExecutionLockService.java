package com.schemaplexai.service.agent.tool.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 工具执行文件系统锁服务
 * <p>Team Agent 并行执行时，多个 member 可能操作同一工作目录下的文件，
 * 通过 ReadWriteLock 保证写操作互斥、读操作可并行。</p>
 * <p>锁粒度为归一化后的文件路径。</p>
 */
@Slf4j
@Component
public class ToolExecutionLockService {

    private final ConcurrentHashMap<String, ReadWriteLock> pathLocks = new ConcurrentHashMap<>();

    /**
     * 在写锁保护下执行操作
     */
    public <T> T executeWithWriteLock(Path path, LockAction<T> action) throws Exception {
        ReadWriteLock lock = pathLocks.computeIfAbsent(normalizeLockKey(path), k -> new ReentrantReadWriteLock(true));
        lock.writeLock().lock();
        try {
            return action.execute();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 在读锁保护下执行操作
     */
    public <T> T executeWithReadLock(Path path, LockAction<T> action) throws Exception {
        ReadWriteLock lock = pathLocks.computeIfAbsent(normalizeLockKey(path), k -> new ReentrantReadWriteLock(true));
        lock.readLock().lock();
        try {
            return action.execute();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 清理不再使用的锁（可由定时任务调用）
     */
    public void evictIdleLocks() {
        pathLocks.entrySet().removeIf(entry -> {
            ReentrantReadWriteLock rwLock = (ReentrantReadWriteLock) entry.getValue();
            return !rwLock.isWriteLocked() && rwLock.getReadLockCount() == 0;
        });
    }

    private String normalizeLockKey(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    @FunctionalInterface
    public interface LockAction<T> {
        T execute() throws Exception;
    }
}

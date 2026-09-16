package com.schemaplexai.service.semantic.infrastructure.jena;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/** 持有共享 TDB2 Dataset，并统一管理事务和关闭流程。 */
@Component
@RequiredArgsConstructor
class SemanticDatasetManager implements AutoCloseable {

    private final SemanticStoreProperties properties;
    private Dataset dataset;

    <T> T read(Function<DatasetGraph, T> action) {
        Dataset current = getOrOpen();
        return Txn.calculateRead(current, () -> action.apply(current.asDatasetGraph()));
    }

    void write(Consumer<DatasetGraph> action) {
        Dataset current = getOrOpen();
        Txn.executeWrite(current, () -> action.accept(current.asDatasetGraph()));
    }

    private synchronized Dataset getOrOpen() {
        if (dataset == null) {
            String location = properties.getDirectory().toAbsolutePath().normalize().toString();
            dataset = TDB2Factory.connectDataset(location);
        }
        return dataset;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (dataset == null) {
            return;
        }
        dataset.close();
        dataset = null;
    }
}

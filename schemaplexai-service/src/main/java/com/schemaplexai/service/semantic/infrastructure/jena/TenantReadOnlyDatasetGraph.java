package com.schemaplexai.service.semantic.infrastructure.jena;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.shared.Lock;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphBase;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Transactional;
import org.apache.jena.sparql.util.Context;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** 不可解包的只读 DatasetGraph 门面，隔离共享 Dataset 的图和生命周期。 */
final class TenantReadOnlyDatasetGraph extends DatasetGraphBase {

    private final DatasetGraph source;
    private final List<Node> visibleGraphs;
    private final Set<Node> allowedGraphs;
    private final PrefixMap prefixes;
    private final Context context;

    TenantReadOnlyDatasetGraph(DatasetGraph source, List<Node> visibleGraphs) {
        this.source = source;
        this.visibleGraphs = List.copyOf(visibleGraphs);
        this.allowedGraphs = Set.copyOf(visibleGraphs);
        this.prefixes = PrefixMapFactory.unmodifiablePrefixMap(PrefixMapFactory.create(source.prefixes()));
        this.context = source.getContext().copy();
    }

    @Override
    public Graph getDefaultGraph() {
        return GraphView.createDefaultGraph(this);
    }

    @Override
    public Graph getGraph(Node graphNode) {
        if (!allowedGraphs.contains(graphNode)) {
            return Graph.emptyGraph;
        }
        return GraphView.createNamedGraph(this, graphNode);
    }

    @Override
    public boolean containsGraph(Node graphNode) {
        return allowedGraphs.contains(graphNode) && source.containsGraph(graphNode);
    }

    @Override
    public Iterator<Node> listGraphNodes() {
        return visibleGraphs.iterator();
    }

    @Override
    public Iterator<Quad> find(Node graph, Node subject, Node predicate, Node object) {
        if (!isWildcard(graph) && !allowedGraphs.contains(graph)) {
            return Collections.emptyIterator();
        }
        return Iter.filter(source.find(graph, subject, predicate, object), this::isAllowed);
    }

    @Override
    public Iterator<Quad> findNG(Node graph, Node subject, Node predicate, Node object) {
        if (!isWildcard(graph) && !allowedGraphs.contains(graph)) {
            return Collections.emptyIterator();
        }
        return Iter.filter(source.findNG(graph, subject, predicate, object), this::isAllowed);
    }

    @Override
    public void addGraph(Node graphName, Graph graph) {
        throw readOnly();
    }

    @Override
    public void removeGraph(Node graphName) {
        throw readOnly();
    }

    @Override
    public void deleteAny(Node graph, Node subject, Node predicate, Node object) {
        throw readOnly();
    }

    @Override
    public void clear() {
        throw readOnly();
    }

    @Override
    public PrefixMap prefixes() {
        return prefixes;
    }

    @Override
    public Context getContext() {
        return context;
    }

    @Override
    public Lock getLock() {
        return source.getLock();
    }

    @Override
    public void begin(TxnType type) {
        if (type != TxnType.READ) {
            throw readOnly();
        }
        source.begin(type);
    }

    @Override
    public boolean promote(Transactional.Promote promoteMode) {
        return false;
    }

    @Override
    public void commit() {
        source.commit();
    }

    @Override
    public void abort() {
        source.abort();
    }

    @Override
    public void end() {
        source.end();
    }

    @Override
    public ReadWrite transactionMode() {
        return source.transactionMode();
    }

    @Override
    public TxnType transactionType() {
        return source.transactionType();
    }

    @Override
    public boolean isInTransaction() {
        return source.isInTransaction();
    }

    @Override
    public boolean supportsTransactions() {
        return source.supportsTransactions();
    }

    @Override
    public boolean supportsTransactionAbort() {
        return source.supportsTransactionAbort();
    }

    @Override
    public void close() {
        // 共享 Dataset 的生命周期仅由 SemanticDatasetManager 管理。
    }

    private boolean isAllowed(Quad quad) {
        return quad != null && allowedGraphs.contains(quad.getGraph());
    }

    private UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("tenant dataset view is read-only");
    }
}

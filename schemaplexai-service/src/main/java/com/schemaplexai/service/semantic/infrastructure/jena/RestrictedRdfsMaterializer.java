package com.schemaplexai.service.semantic.infrastructure.jena;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 仅物化子类、子属性、定义域和值域白名单规则。 */
@Component
class RestrictedRdfsMaterializer {

    private static final Node RDF_TYPE = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
    private static final Node SUB_CLASS = iri("http://www.w3.org/2000/01/rdf-schema#subClassOf");
    private static final Node SUB_PROPERTY = iri("http://www.w3.org/2000/01/rdf-schema#subPropertyOf");
    private static final Node DOMAIN = iri("http://www.w3.org/2000/01/rdf-schema#domain");
    private static final Node RANGE = iri("http://www.w3.org/2000/01/rdf-schema#range");
    private static final Node EQUIVALENT_CLASS = iri("http://www.w3.org/2002/07/owl#equivalentClass");
    private static final Node EQUIVALENT_PROPERTY = iri("http://www.w3.org/2002/07/owl#equivalentProperty");

    Set<Triple> materialize(Graph asserted, int maxInferredTriples) {
        if (maxInferredTriples < 0) {
            throw new IllegalArgumentException("maxInferredTriples must not be negative");
        }
        Set<Triple> base = read(asserted);
        Set<Triple> closure = new LinkedHashSet<>(base);
        addEquivalentRelations(closure, base.size(), maxInferredTriples);

        boolean changed;
        do {
            int sizeBefore = closure.size();
            Map<Node, Set<Node>> subclasses = relationMap(closure, SUB_CLASS);
            Map<Node, Set<Node>> subproperties = relationMap(closure, SUB_PROPERTY);
            closeTransitiveRelation(
                    closure, subclasses, SUB_CLASS, base.size(), maxInferredTriples);
            closeTransitiveRelation(
                    closure, subproperties, SUB_PROPERTY, base.size(), maxInferredTriples);
            applyTypeAndPropertyRules(
                    closure, subclasses, subproperties, base.size(), maxInferredTriples);
            changed = closure.size() != sizeBefore;
        } while (changed);

        closure.removeAll(base);
        return closure;
    }

    private void addEquivalentRelations(Set<Triple> triples, int baseSize, int maxInferredTriples) {
        for (Triple triple : List.copyOf(triples)) {
            if (triple.getPredicate().equals(EQUIVALENT_CLASS)) {
                addInferred(triples, Triple.create(triple.getSubject(), SUB_CLASS, triple.getObject()),
                        baseSize, maxInferredTriples);
                addInferred(triples, Triple.create(triple.getObject(), SUB_CLASS, triple.getSubject()),
                        baseSize, maxInferredTriples);
            } else if (triple.getPredicate().equals(EQUIVALENT_PROPERTY)) {
                addInferred(triples, Triple.create(triple.getSubject(), SUB_PROPERTY, triple.getObject()),
                        baseSize, maxInferredTriples);
                addInferred(triples, Triple.create(triple.getObject(), SUB_PROPERTY, triple.getSubject()),
                        baseSize, maxInferredTriples);
            }
        }
    }

    private void closeTransitiveRelation(
            Set<Triple> triples,
            Map<Node, Set<Node>> relation,
            Node predicate,
            int baseSize,
            int maxInferredTriples) {
        for (Map.Entry<Node, Set<Node>> entry : relation.entrySet()) {
            for (Node direct : entry.getValue()) {
                for (Node transitive : relation.getOrDefault(direct, Set.of())) {
                    addInferred(triples, Triple.create(entry.getKey(), predicate, transitive),
                            baseSize, maxInferredTriples);
                }
            }
        }
    }

    private void applyTypeAndPropertyRules(
            Set<Triple> triples,
            Map<Node, Set<Node>> subclasses,
            Map<Node, Set<Node>> subproperties,
            int baseSize,
            int maxInferredTriples) {
        Map<Node, Set<Node>> domains = relationMap(triples, DOMAIN);
        Map<Node, Set<Node>> ranges = relationMap(triples, RANGE);
        for (Triple triple : new ArrayList<>(triples)) {
            if (triple.getPredicate().equals(RDF_TYPE)) {
                for (Node parent : subclasses.getOrDefault(triple.getObject(), Set.of())) {
                    addInferred(triples, Triple.create(triple.getSubject(), RDF_TYPE, parent),
                            baseSize, maxInferredTriples);
                }
            }
            for (Node parent : subproperties.getOrDefault(triple.getPredicate(), Set.of())) {
                addInferred(triples, Triple.create(triple.getSubject(), parent, triple.getObject()),
                        baseSize, maxInferredTriples);
            }
            for (Node domain : domains.getOrDefault(triple.getPredicate(), Set.of())) {
                addInferred(triples, Triple.create(triple.getSubject(), RDF_TYPE, domain),
                        baseSize, maxInferredTriples);
            }
            if (!triple.getObject().isLiteral()) {
                for (Node range : ranges.getOrDefault(triple.getPredicate(), Set.of())) {
                    addInferred(triples, Triple.create(triple.getObject(), RDF_TYPE, range),
                            baseSize, maxInferredTriples);
                }
            }
        }
    }

    private Map<Node, Set<Node>> relationMap(Set<Triple> triples, Node predicate) {
        Map<Node, Set<Node>> result = new HashMap<>();
        for (Triple triple : triples) {
            if (triple.getPredicate().equals(predicate)) {
                result.computeIfAbsent(triple.getSubject(), ignored -> new HashSet<>()).add(triple.getObject());
            }
        }
        return result;
    }

    private Set<Triple> read(Graph graph) {
        Set<Triple> triples = new LinkedHashSet<>();
        Iterator<Triple> iterator = graph.find(Node.ANY, Node.ANY, Node.ANY);
        iterator.forEachRemaining(triples::add);
        return triples;
    }

    private void addInferred(
            Set<Triple> triples,
            Triple inferred,
            int baseSize,
            int maxInferredTriples) {
        if (triples.add(inferred) && triples.size() - baseSize > maxInferredTriples) {
            throw new IllegalArgumentException("inferred graph exceeds configured triple quota");
        }
    }

    private static Node iri(String value) {
        return NodeFactory.createURI(value);
    }
}

package com.rdf.query;

import java.util.Collections;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import static org.semanticweb.owlapi.util.OWLAPIStreamUtils.asUnorderedSet;
import org.semanticweb.owlapi.util.ShortFormProvider;

public class DLQueryEngine {

    private final OWLReasoner reasoner;
    private final DLQueryParser parser;

    /**
     * Constructs a DLQueryEngine. This will answer "DL queries" using the specified reasoner. A
     * short form provider specifies how entities are rendered.
     *
     * @param reasoner The reasoner to be used for answering the queries.
     * @param shortFormProvider A short form provider.
     */
    public DLQueryEngine(OWLReasoner reasoner, ShortFormProvider shortFormProvider) {
        this.reasoner = reasoner;
        OWLOntology rootOntology = reasoner.getRootOntology();
        parser = new DLQueryParser(rootOntology, shortFormProvider);
    }
    
    /**
     * Gets the superclasses of a class expression parsed from a string.
     *
     * @param classExpressionString The string from which the class expression will be parsed.
     * @param direct Specifies whether direct superclasses should be returned or not.
     * @return The superclasses of the specified class expression If there was a problem parsing the
     *         class expression.
     */
    public Set<OWLClass> getSuperClasses(String classExpressionString, boolean direct) {
        if (classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        OWLClassExpression classExpression = parser.parseClassExpression(classExpressionString);
        NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(classExpression, direct);
        return asUnorderedSet(superClasses.entities());
    }

    /**
     * Gets the equivalent classes of a class expression parsed from a string.
     *
     * @param classExpressionString The string from which the class expression will be parsed.
     * @return The equivalent classes of the specified class expression If there was a problem
     *         parsing the class expression.
     */
    public Set<OWLClass> getEquivalentClasses(String classExpressionString) {
        if (classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        OWLClassExpression classExpression = parser.parseClassExpression(classExpressionString);
        Node<OWLClass> equivalentClasses = reasoner.getEquivalentClasses(classExpression);
        return asUnorderedSet(
            equivalentClasses.entities().filter(cl -> !cl.equals(classExpression)));
    }

    /**
     * Gets the subclasses of a class expression parsed from a string.
     *
     * @param classExpressionString The string from which the class expression will be parsed.
     * @param direct Specifies whether direct subclasses should be returned or not.
     * @return The subclasses of the specified class expression If there was a problem parsing the
     *         class expression.
     */
    public Set<OWLClass> getSubClasses(String classExpressionString, boolean direct) {
        if (classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        OWLClassExpression classExpression = parser.parseClassExpression(classExpressionString);
        NodeSet<OWLClass> subClasses = reasoner.getSubClasses(classExpression, direct);
        return asUnorderedSet(subClasses.entities());
    }

    /**
     * Gets the instances of a class expression parsed from a string.
     *
     * @param classExpressionString The string from which the class expression will be parsed.
     * @param direct Specifies whether direct instances should be returned or not.
     * @return The instances of the specified class expression If there was a problem parsing the
     *         class expression.
     */
    public Set<OWLNamedIndividual> getInstances(String classExpressionString, boolean direct) {
        if (classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        OWLClassExpression classExpression = parser.parseClassExpression(classExpressionString);
        NodeSet<OWLNamedIndividual> individuals = reasoner.getInstances(classExpression, direct);
        return asUnorderedSet(individuals.entities());
    }
}


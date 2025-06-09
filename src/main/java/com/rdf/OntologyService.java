package com.rdf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.util.ShortFormProvider;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;

import com.rdf.query.DLQueryEngine;

/**
 * OntologyService is responsible for:
 *   1) Loading four files:
 *        • smartFlow.ttl      (SmartFlow schema + sf:has_nextNode chain)
 *        • instanceTtl        (RML‐generated SmartFlow individuals)
 *        • bpmn.ttl           (BBO/BPMN ontology, including bbo:SequenceFlow, bbo:ExclusiveGateway, etc.)
 *        • mapping.ttl        (SmartFlow↔BPMN alignments)
 *   2) Merging them into one OWLOntology.
 *   3) Running HermiT (precomputeInferences) so that every sf:has_nextNode(x,y) is materialized.
 *   4) Programmatically creating:
 *        • If x has exactly one inferred sf:has_nextNode, a direct bbo:SequenceFlow x→y.
 *        • If x has two or more inferred sf:has_nextNode targets, a bbo:ExclusiveGateway_x plus:
 *            – a SequenceFlow x→ExclusiveGateway_x
 *            – one SequenceFlow ExclusiveGateway_x→each y
 *   5) Assigning each new SequenceFlow and ExclusiveGateway a bbo:id and bbo:name.
 *   6) Providing DL‐query helpers (getSubClasses, getInstances, etc.).
 *   7) Providing “inferred properties by class” methods.
 *   8) Exposing print methods so that App.java can simply call them.
 *   9) Offering a method to retrieve all bbo:FlowNode (and subclasses) individuals as a List.
 *  10) Providing a public getShortForm(...) helper to retrieve short forms.
 *  11) Saving the final merged ontology (including newly created elements) to TTL.
 */
public class OntologyService {

    // --------------------------------------------------------
    //  Fields
    // --------------------------------------------------------

    private final OWLOntologyManager manager;
    private final OWLOntology mergedOntology;
    private final OWLReasoner reasoner;
    private final OWLDataFactory df;

    private final ShortFormProvider shortFormProvider;
    private final DLQueryEngine dlQueryEngine;

    // Namespaces
    private static final String SF_NS   = "http://example.org/ontologies/SmartFlow#";
    private static final String BPMN_NS = "https://www.irit.fr/recherches/MELODI/ontologies/BBO#";

    // Cached classes/properties (examples)
    private final OWLClass actionNodeCls;
    private final OWLClass userTaskCls;

    private final OWLDataProperty nodeIdProp;
    private final OWLObjectProperty queueProp;

    // The “sf:has_nextNode” property
    private final OWLObjectProperty hasNextNodeProp;

    // --------------------------------------------------------
    //  Constructor
    // --------------------------------------------------------

    /**
     * @param schemaTtl    Path to smartFlow.ttl (defines sf:has_nextNode chain, classes, properties).
     * @param instanceTtl  Path to the RML output TTL (SmartFlow instances, including sf:has_action & sf:has_transitionsTo).
     * @param bpmnTtl      Path to bpmn.ttl (defines bbo:SequenceFlow, bbo:ExclusiveGateway, bbo:FlowNode, etc.).
     * @param mappingTtl   Path to mapping.ttl (SmartFlow↔BPMN class/property alignments).
     *
     * @throws OWLOntologyCreationException if any ontology file fails to load.
     * @throws IOException 
     */
    public OntologyService(Path schemaTtl,
                           Path instanceTtl,
                           Path bpmnTtl,
                           Path mappingTtl) throws OWLOntologyCreationException, IOException
    {
        // 1) Create manager & data factory
        this.manager = OWLManager.createOWLOntologyManager();
        this.df      = manager.getOWLDataFactory();

        Path mergedTtl = mappingTtl;
        if (instanceTtl != null) {
            mergedTtl = mergeTtlFiles(mappingTtl, instanceTtl);
        } else {
            throw new IllegalArgumentException("instanceTtl may not be null");
        }

        // 3) Load ontologies
        OWLOntology sfSchema = manager.loadOntologyFromOntologyDocument(schemaTtl.toFile());
        OWLOntology bpmnOnt  = manager.loadOntologyFromOntologyDocument(bpmnTtl.toFile());
        OWLOntology mapOnt   = manager.loadOntologyFromOntologyDocument(mergedTtl.toFile());

        // 4) Merge them into one ontology
        this.mergedOntology = manager.createOntology();
        manager.addAxioms(mergedOntology, sfSchema.getAxioms());
        manager.addAxioms(mergedOntology, bpmnOnt.getAxioms());
        manager.addAxioms(mergedOntology, mapOnt.getAxioms());


        // 4) Run HermiT classification / inference
        OWLReasonerFactory reasonerFactory = new ReasonerFactory();
        this.reasoner = reasonerFactory.createReasoner(mergedOntology);
        reasoner.precomputeInferences(
            InferenceType.CLASS_HIERARCHY,
            InferenceType.OBJECT_PROPERTY_HIERARCHY,
            InferenceType.CLASS_ASSERTIONS,
            InferenceType.OBJECT_PROPERTY_ASSERTIONS
        );

        // 5) Cache some OWLClass / OWLProperty references BEFORE materialization
        this.actionNodeCls   = df.getOWLClass(IRI.create(SF_NS + "ActionNode"));
        this.userTaskCls     = df.getOWLClass(IRI.create(BPMN_NS + "UserTask"));
        this.nodeIdProp      = df.getOWLDataProperty(IRI.create(SF_NS + "sfId"));
        this.hasNextNodeProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_nextNode"));
        this.queueProp       = df.getOWLObjectProperty(IRI.create(SF_NS + "has_queue"));

        // 6) Programmatically create bbo:SequenceFlow (and bbo:ExclusiveGateway if needed) for every inferred (x sf:has_nextNode y)
        materializeSequenceFlowsFromNextNode();
        assignQueueToTerminalNodes();


        // 7) Prepare DLQueryEngine for Manchester‐syntax queries
        this.shortFormProvider = new SimpleShortFormProvider();
        this.dlQueryEngine     = new DLQueryEngine(reasoner, shortFormProvider);
    }

    /**
     * Produce a single TTL file that is:
     *   mapping.ttl + (individuals.ttl without any @prefix/@base lines)
     */
    private Path mergeTtlFiles(Path mappingTtl, Path individualsTtl) throws IOException {
        Path merged = Files.createTempFile("merged-ontology-", ".ttl");
        try (BufferedWriter writer = Files.newBufferedWriter(merged);
            BufferedReader mapIn = Files.newBufferedReader(mappingTtl);
            BufferedReader instIn = Files.newBufferedReader(individualsTtl)) {

            // 1) copy the entire mapping file
            String line;
            while ((line = mapIn.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }

            writer.newLine();  // just a separator

            // 2) append individuals, skipping prefixes
            while ((line = instIn.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("@prefix") || t.startsWith("@base") || t.isEmpty()) {
                    continue;
                }
                writer.write(line);
                writer.newLine();
            }
        }
        return merged;
    }

    // --------------------------------------------------------
    //  DL‐Query Helper Methods
    // --------------------------------------------------------

    /** Return direct or indirect subclasses of a Manchester‐syntax class expression. */
    public Set<OWLClass> getSubClasses(String classExpressionString, boolean direct) {
        if (classExpressionString == null || classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return dlQueryEngine.getSubClasses(classExpressionString, direct);
        } catch (Exception ex) {
            return Collections.emptySet();
        }
    }

    /** Return direct or indirect superclasses of a Manchester‐syntax class expression. */
    public Set<OWLClass> getSuperClasses(String classExpressionString, boolean direct) {
        if (classExpressionString == null || classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return dlQueryEngine.getSuperClasses(classExpressionString, direct);
        } catch (Exception ex) {
            return Collections.emptySet();
        }
    }

    /** Return all equivalent classes of a Manchester‐syntax class expression. */
    public Set<OWLClass> getEquivalentClasses(String classExpressionString) {
        if (classExpressionString == null || classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return dlQueryEngine.getEquivalentClasses(classExpressionString);
        } catch (Exception ex) {
            return Collections.emptySet();
        }
    }

    /**
     * Return all individuals (OWLNamedIndividual) that satisfy the given Manchester‐syntax class expression.
     * @param classExpressionString e.g. "ActionNode"
     * @param direct                true = only direct instances; false = include subclass‐instances.
     */
    public Set<OWLNamedIndividual> getInstances(String classExpressionString, boolean direct) {
        if (classExpressionString == null || classExpressionString.trim().isEmpty()) {
            return Collections.emptySet();
        }
        try {
            return dlQueryEngine.getInstances(classExpressionString, direct);
        } catch (Exception ex) {
            return Collections.emptySet();
        }
    }

    // --------------------------------------------------------
    //  “sf:has_nextNode” → bbo:SequenceFlow + ExclusiveGateway + id/name
    // --------------------------------------------------------

    /**
     * For every inferred (x sf:has_nextNode y):
     *   • If x has exactly one y, create one bbo:SequenceFlow x→y.
     *   • If x has two or more y’s, create:
     *       – A bbo:ExclusiveGateway individual “ExclusiveGateway_x”
     *       – A SequenceFlow x→ExclusiveGateway_x
     *       – A SequenceFlow ExclusiveGateway_x→each y
     *   • Assign each new SequenceFlow and ExclusiveGateway a bbo:id and bbo:name.
     *
     * Must be called only after precomputeInferences(...),
     * and only after hasNextNodeProp has been initialized.
     */
    public final void materializeSequenceFlowsFromNextNode() {
        // 1) BBO namespace & OWL entities
        String BBO_NS = BPMN_NS; // "https://www.irit.fr/recherches/MELODI/ontologies/BBO#"
        OWLClass sequenceFlowClass    = df.getOWLClass(IRI.create(BBO_NS + "SequenceFlow"));
        OWLObjectProperty srcProp     = df.getOWLObjectProperty(IRI.create(BBO_NS + "has_sourceRef"));
        OWLObjectProperty tgtProp     = df.getOWLObjectProperty(IRI.create(BBO_NS + "has_targetRef"));
        OWLDataProperty bboIdProp     = df.getOWLDataProperty(IRI.create(BBO_NS + "id"));
        OWLDataProperty bboNameProp   = df.getOWLDataProperty(IRI.create(BBO_NS + "name"));
        OWLObjectProperty hasActionProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_action"));
        OWLClass exclusiveGatewayCls  = df.getOWLClass(IRI.create(BBO_NS + "ExclusiveGateway"));
        // (If you want to use default‐branch marking, uncomment the next line)
        // OWLObjectProperty hasExGwProp = df.getOWLObjectProperty(IRI.create(BBO_NS + "has_exclusiveGateway"));

        // 2) For each individual “subj” in the ontology signature:
        for (OWLNamedIndividual subj : mergedOntology.getIndividualsInSignature()) {
            // a) Ask HermiT: all y such that (subj, sf:has_nextNode, y)
            NodeSet<OWLNamedIndividual> targets = reasoner.getObjectPropertyValues(subj, hasNextNodeProp);
            Set<OWLNamedIndividual> nextNodes  = targets.entities().collect(Collectors.toSet());

            // b) If no nextNodes, skip
            if (nextNodes.isEmpty()) {
                continue;
            }

            // c) If exactly one target → create simple SequenceFlow subj→y
            if (nextNodes.size() == 1) {
                OWLNamedIndividual obj   = nextNodes.iterator().next();
                String subjFrag = subj.getIRI().getFragment();
                String objFrag  = obj .getIRI().getFragment();
                String seqFrag  = subjFrag + "_to_" + objFrag;
                OWLNamedIndividual seq   = df.getOWLNamedIndividual(IRI.create(BBO_NS + seqFrag));

                // i) seq a bbo:SequenceFlow
                manager.addAxiom(mergedOntology,
                    df.getOWLClassAssertionAxiom(sequenceFlowClass, seq));

                // ii) seq bbo:has_sourceRef subj
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(srcProp, seq, subj));

                // iii) seq bbo:has_targetRef obj
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(tgtProp, seq, obj));

                // iv) seq bbo:id = "SequenceFlow_subj_to_obj"
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboIdProp, seq, df.getOWLLiteral(seqFrag)));

                // v) seq bbo:name = "SequenceFlow from subj to obj"
                String humanName = subjFrag + "_to_" + objFrag;
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboNameProp, seq, df.getOWLLiteral(humanName)));

            } else {
                // d) Two or more targets → create a single ExclusiveGateway + multiple SequenceFlows
                String subjFrag = subj.getIRI().getFragment();
                String gwFrag   = "XOR_" + subjFrag;
                String gwIRI    = BBO_NS + gwFrag;
                OWLNamedIndividual gw = df.getOWLNamedIndividual(IRI.create(gwIRI));

                // i) gw a bbo:ExclusiveGateway
                manager.addAxiom(mergedOntology,
                    df.getOWLClassAssertionAxiom(exclusiveGatewayCls, gw));

                // ii) gw bbo:id = "ExclusiveGateway_subj"; gw bbo:name = "ExclusiveGateway for subj"
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboIdProp, gw, df.getOWLLiteral(gwFrag)));
                String gwHuman = "XOR_" + subjFrag;
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboNameProp, gw, df.getOWLLiteral(gwHuman)));

                mergedOntology.getObjectPropertyAssertionAxioms(subj).stream()
                    .filter(ax -> ax.getProperty().asOWLObjectProperty().equals(queueProp))
                    .map(ax -> ax.getObject().asOWLNamedIndividual())
                    .forEach(queueInd -> {
                        manager.addAxiom(mergedOntology,
                            df.getOWLObjectPropertyAssertionAxiom(queueProp, gw, queueInd));
                    });

                // iii) Create one SequenceFlow subj→gw
                String seqToGwFrag = subjFrag + "_to_" + gwFrag;
                String seqToGwIRI  = BBO_NS + seqToGwFrag;
                OWLNamedIndividual seqToGw = df.getOWLNamedIndividual(IRI.create(seqToGwIRI));

                // a) seqToGw a bbo:SequenceFlow
                manager.addAxiom(mergedOntology,
                    df.getOWLClassAssertionAxiom(sequenceFlowClass, seqToGw));

                // b) seqToGw bbo:has_sourceRef subj
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(srcProp, seqToGw, subj));

                // c) seqToGw bbo:has_targetRef gw
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(tgtProp, seqToGw, gw));

                // d) seqToGw bbo:id and bbo:name
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboIdProp, seqToGw, df.getOWLLiteral(seqToGwFrag)));
                String seqToGwName = subjFrag + "_to_" + gwFrag;
                manager.addAxiom(mergedOntology,
                    df.getOWLDataPropertyAssertionAxiom(bboNameProp, seqToGw, df.getOWLLiteral(seqToGwName)));

                // (Optional) mark default: 
                // manager.addAxiom(mergedOntology,
                //     df.getOWLObjectPropertyAssertionAxiom(hasExGwProp, seqToGw, gw));

                // iv) For each target y, create SequenceFlow gw→y
                for (OWLNamedIndividual obj : nextNodes) {
                    String objFrag      = obj.getIRI().getFragment();
                    String seqFromGwFrag = gwFrag + "_to_" + objFrag;
                    String seqFromGwIRI  = BBO_NS + seqFromGwFrag;
                    OWLNamedIndividual seqFromGw = df.getOWLNamedIndividual(IRI.create(seqFromGwIRI));

                    // a) seqFromGw a bbo:SequenceFlow
                    manager.addAxiom(mergedOntology,
                        df.getOWLClassAssertionAxiom(sequenceFlowClass, seqFromGw));

                    // b) seqFromGw bbo:has_sourceRef gw
                    manager.addAxiom(mergedOntology,
                        df.getOWLObjectPropertyAssertionAxiom(srcProp, seqFromGw, gw));

                    // c) seqFromGw bbo:has_targetRef obj
                    manager.addAxiom(mergedOntology,
                        df.getOWLObjectPropertyAssertionAxiom(tgtProp, seqFromGw, obj));

                    // d) seqFromGw bbo:id and bbo:name
                    manager.addAxiom(mergedOntology,
                        df.getOWLDataPropertyAssertionAxiom(bboIdProp, seqFromGw, df.getOWLLiteral(seqFromGwFrag)));
                    String seqFromGwName = gwFrag + "_to_" + objFrag;
                    manager.addAxiom(mergedOntology,
                        df.getOWLDataPropertyAssertionAxiom(bboNameProp, seqFromGw, df.getOWLLiteral(seqFromGwName)));
                }
            }
        }
        reasoner.flush();
    }

    // --------------------------------------------------------
    //  Getters for “inferred nextNode” and “all sequenceFlow” Links
    // --------------------------------------------------------

    /**
     * Return, for a given individual x, all inferred sf:has_nextNode targets y.
     * @param node an OWLNamedIndividual
     * @return Set of OWLNamedIndividual y such that (node, sf:has_nextNode, y) holds.
     */
    public Set<OWLNamedIndividual> getInferredNextNodes(OWLNamedIndividual node) {
        if (node == null) {
            return Collections.emptySet();
        }
        NodeSet<OWLNamedIndividual> targets =
            reasoner.getObjectPropertyValues(node, hasNextNodeProp);
        return targets.entities().collect(Collectors.toSet());
    }

    /**
     * Return a Map of every (x → {y1,y2,…}) where HermiT has inferred (x, sf:has_nextNode, y).
     * @return Map whose key = OWLNamedIndividual x, value = Set<OWLNamedIndividual> of all y’s.
     */
    public Map<OWLNamedIndividual, Set<OWLNamedIndividual>> getAllInferredNextNodeLinks() {
        Map<OWLNamedIndividual, Set<OWLNamedIndividual>> result = new HashMap<>();
        for (OWLNamedIndividual subj : mergedOntology.getIndividualsInSignature()) {
            NodeSet<OWLNamedIndividual> targets =
                reasoner.getObjectPropertyValues(subj, hasNextNodeProp);
            Set<OWLNamedIndividual> targetSet =
                targets.entities().collect(Collectors.toSet());
            if (!targetSet.isEmpty()) {
                result.put(subj, targetSet);
            }
        }
        return result;
    }

    /**
     * Return a Set of all OWLNamedIndividual that are typed as bbo:SequenceFlow.
     * (i.e., those we programmatically created in materializeSequenceFlowsFromNextNode).
     */
    public Set<OWLNamedIndividual> getAllSequenceFlowIndividuals() {
        OWLClass seqFlowClass = df.getOWLClass(IRI.create(BPMN_NS + "SequenceFlow"));
        return mergedOntology.getIndividualsInSignature().stream()
            .filter(ind -> mergedOntology.getClassAssertionAxioms(ind).stream()
                .anyMatch(ax -> ax.getClassesInSignature().stream()
                    .anyMatch(c -> c.equals(seqFlowClass))))
            .collect(Collectors.toSet());
    }

    /**
     * Return a Set of all OWLNamedIndividual that are typed as bbo:ExclusiveGateway.
     */
    public Set<OWLNamedIndividual> getAllExclusiveGatewayIndividuals() {
        OWLClass gwClass    = df.getOWLClass(IRI.create(BPMN_NS + "ExclusiveGateway"));
        return mergedOntology.getIndividualsInSignature().stream()
            .filter(ind -> mergedOntology.getClassAssertionAxioms(ind).stream()
                .anyMatch(ax -> ax.getClassesInSignature().stream()
                    .anyMatch(c -> c.equals(gwClass))))
            .collect(Collectors.toSet());
    }

    /**
     * Return a List of all OWLNamedIndividual that are instances of bbo:FlowNode
     * or any subclass of bbo:FlowNode (asserted or inferred).
     *
     * Internally, this calls reasoner.getInstances(flowNodeCls, false), which returns
     * all individuals whose type is flowNodeCls or any subclass thereof.
     *
     * @return List of OWLNamedIndividual for class bbo:FlowNode (and subclasses).
     */
    public List<OWLNamedIndividual> getAllFlowNodeIndividuals() {
        // Look up the FlowNode class from the BPMN namespace
        OWLClass flowNodeClass = df.getOWLClass(IRI.create(BPMN_NS + "FlowNode"));

        // Ask the reasoner for all instances (including those inferred via subclass)
        NodeSet<OWLNamedIndividual> instances = reasoner.getInstances(flowNodeClass, false);

        // Convert NodeSet to a List<OWLNamedIndividual>
        return instances.entities().collect(Collectors.toList());
    }

    public List<OWLNamedIndividual> getAllLaneIndividuals() {
        // Look up the Lane class from the BPMN namespace
        OWLClass laneClass = df.getOWLClass(IRI.create(BPMN_NS + "Lane"));
        // Ask the reasoner for all instances (including those inferred via subclass)
        NodeSet<OWLNamedIndividual> instances = reasoner.getInstances(laneClass, false);
        // Convert NodeSet to a List<OWLNamedIndividual>
        return instances.entities().collect(Collectors.toList());
    }

    /**
     * Print every FlowNode (or subclass) individual, prefixed by its direct type(s).
     * Example output line: "Task: task-ApproveLeave"
     */
    public void printAllFlowNodesWithClass() {
        System.out.println("\n=== All bbo:FlowNode (and subclasses) with direct class ===");
        List<OWLNamedIndividual> flowNodes = getAllFlowNodeIndividuals();
        if (flowNodes.isEmpty()) {
            System.out.println("  [No FlowNode individuals found]");
            return;
        }
        for (OWLNamedIndividual fn : flowNodes) {
            // Get direct types (classes) of this individual
            Set<OWLClass> directTypes = reasoner.getTypes(fn, true)
                                                 .entities()
                                                 .collect(Collectors.toSet());
            // For each direct type, print "TypeShortForm: IndividualShortForm"
            for (OWLClass type : directTypes) {
                String typeSf = shortFormProvider.getShortForm(type);
                String indSf  = shortFormProvider.getShortForm(fn);
                System.out.println("  " + typeSf + ": " + indSf);
            }
        }
    }

    /**
     * Print every SequenceFlow (or subclass) individual, prefixed by its direct type(s).
     * To find direct types we consult the ontology’s ClassAssertion axioms (since the reasoner
     * wasn’t updated after adding SequenceFlow assertions).
     * Example output line: "SequenceFlow: SequenceFlow_step-Upload_to_step-HR"
     */
    public void printAllSequenceFlowsWithClass() {
        System.out.println("\n=== All bbo:SequenceFlow (and subclasses) with direct class ===");
        Set<OWLNamedIndividual> seqFlows = getAllSequenceFlowIndividuals();
        if (seqFlows.isEmpty()) {
            System.out.println("  [No SequenceFlow individuals found]");
            return;
        }
        for (OWLNamedIndividual sf : seqFlows) {
            // Collect all asserted rdf:type classes from the ontology
            Set<OWLClass> assertedTypes = mergedOntology.getClassAssertionAxioms(sf).stream()
                .map(OWLClassAssertionAxiom::getClassExpression)
                .filter(expr -> !expr.isAnonymous())
                .map(expr -> expr.asOWLClass())
                .collect(Collectors.toSet());

            if (assertedTypes.isEmpty()) {
                // If somehow no asserted type, fall back to “Thing”
                String indSf = shortFormProvider.getShortForm(sf);
                System.out.println("  Thing: " + indSf);
            } else {
                // Print each asserted type in short form
                for (OWLClass type : assertedTypes) {
                    String typeSf = shortFormProvider.getShortForm(type);
                    String indSf  = shortFormProvider.getShortForm(sf);
                    System.out.println("  " + typeSf + ": " + indSf);
                }
            }
        }
    }

    private void assignQueueToTerminalNodes() {
        // 1) find all ActionNode individuals
        OWLClass actionNodeCls = df.getOWLClass(IRI.create(SF_NS + "StartNode"));
        Set<OWLNamedIndividual> actionNodes =
            reasoner.getInstances(actionNodeCls, /*direct=*/false)
                    .entities().collect(Collectors.toSet());

        if (actionNodes.isEmpty()) {
            // no ActionNodes → nothing to copy
            return;
        }

        // 2) pick the "first" ActionNode
        OWLNamedIndividual firstAction = actionNodes.iterator().next();

        // 3) collect its sf:has_queue targets
        Set<OWLNamedIndividual> queueTargets = mergedOntology
            .getObjectPropertyAssertionAxioms(firstAction).stream()
            .filter(ax -> ax.getProperty().asOWLObjectProperty().equals(queueProp))
            .map(ax -> ax.getObject().asOWLNamedIndividual())
            .collect(Collectors.toSet());

        if (queueTargets.isEmpty()) {
            // first ActionNode has no queue → nothing to copy
            return;
        }

        // 4) for each TerminalNode, add the same has_queue assertions
        OWLClass terminalNodeCls = df.getOWLClass(IRI.create(SF_NS + "TerminalNode"));
        Set<OWLNamedIndividual> terminals =
            reasoner.getInstances(terminalNodeCls, /*direct=*/false)
                    .entities().collect(Collectors.toSet());

        for (OWLNamedIndividual term : terminals) {
            for (OWLNamedIndividual queueInd : queueTargets) {
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(queueProp, term, queueInd));
            }
        }
        reasoner.flush();
    }

    /**
     * Return all sf:Queue individuals linked from the given element via sf:has_queue.
     * Because you’ve aligned sf:Queue ≡ bbo:Lane in mapping.ttl, we can treat these
     * queue individuals as BPMN Lanes.
     *
     * @param element  an OWLNamedIndividual (e.g. an ActionNode)
     * @return         possibly empty set of queue individuals
     */
    public Set<OWLNamedIndividual> getQueuesForElement(OWLNamedIndividual element) {
        if (element == null) {
            return Collections.emptySet();
        }
        // sf:has_queue
        OWLObjectProperty hasQueueProp =
            df.getOWLObjectProperty(IRI.create(SF_NS + "has_queue"));
        NodeSet<OWLNamedIndividual> queues =
            reasoner.getObjectPropertyValues(element, hasQueueProp);
        return queues.entities().collect(Collectors.toSet());
    }

    // --------------------------------------------------------
    //  Expose shortFormProvider via a public helper
    // --------------------------------------------------------

    /**
     * Return the short form (e.g. just the fragment) of any OWLEntity (individual or class).
     * Mainly used by App.java so it does not need to access shortFormProvider directly.
     */
    public String getShortForm(OWLNamedIndividual ind) {
        return shortFormProvider.getShortForm(ind);
    }

    // --------------------------------------------------------
    //  Print Methods
    // --------------------------------------------------------

    /**
     * Print all inferred sf:has_nextNode links to stdout in short form:
     *   x --has_nextNode--> y
     */
    public void printAllInferredNextNodeLinks() {
        Map<OWLNamedIndividual, Set<OWLNamedIndividual>> nextNodeMap =
            getAllInferredNextNodeLinks();

        System.out.println("\n=== All inferred sf:has_nextNode links ===");
        if (nextNodeMap.isEmpty()) {
            System.out.println("  [No inferred sf:has_nextNode links found]");
            return;
        }
        nextNodeMap.forEach((subj, targets) -> {
            String subjId = shortFormProvider.getShortForm(subj);
            for (OWLNamedIndividual obj : targets) {
                String objId = shortFormProvider.getShortForm(obj);
                System.out.println("  " + subjId + " --has_nextNode--> " + objId);
            }
        });
    }

    /**
     * Print, for a given OWLNamedIndividual x, its inferred sf:has_nextNode targets:
     *   x --has_nextNode--> y1
     *   x --has_nextNode--> y2
     */
    public void printInferredNextNodesOf(OWLNamedIndividual node) {
        if (node == null) {
            System.out.println("[Cannot print inferred nextNodes: node is null]");
            return;
        }
        String nodeId = shortFormProvider.getShortForm(node);
        Set<OWLNamedIndividual> targets = getInferredNextNodes(node);

        System.out.println("\n=== Inferred sf:has_nextNode targets of “" + nodeId + "” ===");
        if (targets.isEmpty()) {
            System.out.println("  [no inferred sf:has_nextNode for “" + nodeId + "”]");
            return;
        }
        for (OWLNamedIndividual obj : targets) {
            String objId = shortFormProvider.getShortForm(obj);
            System.out.println("  " + nodeId + " --has_nextNode--> " + objId);
        }
    }

    /**
     * Print all bbo:SequenceFlow individuals along with their has_sourceRef, has_targetRef, id, and name.
     * Format:
     *   SequenceFlow_…  has_sourceRef=x  has_targetRef=y  id="…"  name="…"
     */
    public void printAllSequenceFlows() {
        System.out.println("\n=== All bbo:SequenceFlow instances ===");

        OWLClass seqFlowClass   = df.getOWLClass(IRI.create(BPMN_NS + "SequenceFlow"));

        OWLObjectProperty srcProp = df.getOWLObjectProperty(IRI.create(BPMN_NS + "has_sourceRef"));
        OWLObjectProperty tgtProp = df.getOWLObjectProperty(IRI.create(BPMN_NS + "has_targetRef"));
        OWLDataProperty idProp   = df.getOWLDataProperty(IRI.create(BPMN_NS + "id"));
        OWLDataProperty nameProp = df.getOWLDataProperty(IRI.create(BPMN_NS + "name"));

        Set<OWLNamedIndividual> seqFlows = getAllSequenceFlowIndividuals();
        if (seqFlows.isEmpty()) {
            System.out.println("  [No bbo:SequenceFlow individuals found]");
            return;
        }

        for (OWLNamedIndividual seq : seqFlows) {
            String seqId = shortFormProvider.getShortForm(seq);

            // has_sourceRef
            Set<String> sources = mergedOntology.getObjectPropertyAssertionAxioms(seq).stream()
                .filter(ax -> ax.getProperty().asOWLObjectProperty().equals(srcProp))
                .map(ax -> shortFormProvider.getShortForm(ax.getObject().asOWLNamedIndividual()))
                .collect(Collectors.toSet());

            // has_targetRef
            Set<String> targets = mergedOntology.getObjectPropertyAssertionAxioms(seq).stream()
                .filter(ax -> ax.getProperty().asOWLObjectProperty().equals(tgtProp))
                .map(ax -> shortFormProvider.getShortForm(ax.getObject().asOWLNamedIndividual()))
                .collect(Collectors.toSet());

            // id literal
            Set<String> ids = mergedOntology.getDataPropertyAssertionAxioms(seq).stream()
                .filter(ax -> ax.getProperty().asOWLDataProperty().equals(idProp))
                .map(ax -> ax.getObject().getLiteral())
                .collect(Collectors.toSet());

            // name literal
            Set<String> names = mergedOntology.getDataPropertyAssertionAxioms(seq).stream()
                .filter(ax -> ax.getProperty().asOWLDataProperty().equals(nameProp))
                .map(ax -> ax.getObject().getLiteral())
                .collect(Collectors.toSet());

            // Print all combinations (usually one of each)
            for (String s : sources) {
                for (String t : targets) {
                    for (String idVal : ids) {
                        for (String nameVal : names) {
                            System.out.println("  " + seqId +
                                               "  has_sourceRef=" + s +
                                               ", has_targetRef="  + t +
                                               ", id=\""          + idVal +
                                               "\", name=\""      + nameVal + "\"");
                        }
                    }
                }
            }
        }
    }

    /**
     * Print all bbo:ExclusiveGateway individuals along with their id and name.
     * Format:
     *   ExclusiveGateway_…  id="…"  name="…"
     */
    public void printAllExclusiveGateways() {
        System.out.println("\n=== All bbo:ExclusiveGateway instances ===");

        OWLClass gwClass    = df.getOWLClass(IRI.create(BPMN_NS + "ExclusiveGateway"));
        OWLDataProperty idProp   = df.getOWLDataProperty(IRI.create(BPMN_NS + "id"));
        OWLDataProperty nameProp = df.getOWLDataProperty(IRI.create(BPMN_NS + "name"));

        Set<OWLNamedIndividual> gws = getAllExclusiveGatewayIndividuals();
        if (gws.isEmpty()) {
            System.out.println("  [No bbo:ExclusiveGateway individuals found]");
            return;
        }

        for (OWLNamedIndividual gw : gws) {
            String gwId = shortFormProvider.getShortForm(gw);

            // id literal
            Set<String> ids = mergedOntology.getDataPropertyAssertionAxioms(gw).stream()
                .filter(ax -> ax.getProperty().asOWLDataProperty().equals(idProp))
                .map(ax -> ax.getObject().getLiteral())
                .collect(Collectors.toSet());

            // name literal
            Set<String> names = mergedOntology.getDataPropertyAssertionAxioms(gw).stream()
                .filter(ax -> ax.getProperty().asOWLDataProperty().equals(nameProp))
                .map(ax -> ax.getObject().getLiteral())
                .collect(Collectors.toSet());

            for (String idVal : ids) {
                for (String nameVal : names) {
                    System.out.println("  " + gwId + "  id=\"" + idVal + "\", name=\"" + nameVal + "\"");
                }
            }
        }
    }

    public void printAllLanesIndidividuals() {
        System.out.println("\n=== All bbo:Lane instances ===");

        List<OWLNamedIndividual> laneInd = getAllLaneIndividuals();
        if (laneInd.isEmpty()) {
            System.out.println("  [No bbo:Lane individuals found]");
            return;
        }
        for (OWLNamedIndividual lane : laneInd) {
            String laneId = shortFormProvider.getShortForm(lane);
            Set<OWLClass> types = reasoner.getTypes(lane, true).entities().collect(Collectors.toSet());
            String typeNames = types.stream()
                .map(shortFormProvider::getShortForm)
                .collect(Collectors.joining(", "));

            System.out.println("  Lane: " + laneId + " (types: " + typeNames + ")");
        }
    }

    // --------------------------------------------------------
    //  “Inferred Properties for Each Class” Methods
    // --------------------------------------------------------

    /**
     * For a given named OWLClass C, return all OWLObjectProperties P such that
     * HermiT can prove: C ⊑ ∃P.⊤
     */
    public Set<OWLObjectProperty> getInferredObjectProperties(OWLClass cls) {
        if (cls == null) {
            return Collections.emptySet();
        }
        return mergedOntology.getObjectPropertiesInSignature().stream()
            .filter(prop -> {
                OWLClassExpression existsRestriction =
                    df.getOWLObjectSomeValuesFrom(prop, df.getOWLThing());
                NodeSet<OWLClass> superOfRestr =
                    reasoner.getSuperClasses(existsRestriction, false);
                return superOfRestr.entities().anyMatch(c -> c.equals(cls));
            })
            .collect(Collectors.toSet());
    }

    /**
     * For a given named OWLClass C, return all OWLDataProperties D such that
     * HermiT can prove: C ⊑ ∃D.⊤_datatype
     */
    public Set<OWLDataProperty> getInferredDataProperties(OWLClass cls) {
        if (cls == null) {
            return Collections.emptySet();
        }
        return mergedOntology.getDataPropertiesInSignature().stream()
            .filter(dataProp -> {
                OWLClassExpression existsDataRestriction =
                    df.getOWLDataSomeValuesFrom(dataProp, df.getTopDatatype());
                NodeSet<OWLClass> superOfDataRestr =
                    reasoner.getSuperClasses(existsDataRestriction, false);
                return superOfDataRestr.entities().anyMatch(c -> c.equals(cls));
            })
            .collect(Collectors.toSet());
    }

    // --------------------------------------------------------
    //  Public getters
    // --------------------------------------------------------

    /** Return the merged OWLOntology (with newly created SequenceFlows and ExclusiveGateways). */
    public OWLOntology getMergedOntology() {
        return mergedOntology;
    }

    /** Return all named OWLClass in the merged ontology signature. */
    public Set<OWLClass> getAllClassesInSignature() {
        return mergedOntology.getClassesInSignature();
    }

        /**
     * Return the fragment of the IRI of this individual (e.g. "SequenceFlow_A_to_B").
     */
    public String getIndividualId(OWLNamedIndividual ind) {
        return ind.getIRI().getFragment();
    }

    /**
     * Return the literal value of the given data‐property (by local name) on this individual,
     * or null if not present.
     *
     * Example: getDataPropertyValue(seqInd, "name") will return the bbo:name literal.
     */
    public String getDataPropertyValue(OWLNamedIndividual ind, String propertyLocalName) {
        return mergedOntology.getDataPropertyAssertionAxioms(ind).stream()
            .filter(ax -> ax.getProperty().asOWLDataProperty()
                            .getIRI().getFragment().equals(propertyLocalName))
            .map(ax -> ax.getObject().getLiteral())
            .findFirst().orElse(null);
    }

    // --------------------------------------------------------
    //  Save to Turtle
    // --------------------------------------------------------

    /**
     * Save the current merged ontology (including all newly created bbo:SequenceFlow and bbo:ExclusiveGateway instances)
     * to a Turtle file. Useful for opening in Protégé.
     */
    public void saveMergedOntologyAsTurtle(Path outputTtlPath)
            throws OWLOntologyStorageException, IOException
    {
        try (FileOutputStream out = new FileOutputStream(outputTtlPath.toFile())) {
            OWLDocumentFormat turtleFormat = new TurtleDocumentFormat();
            manager.saveOntology(mergedOntology, turtleFormat, out);
        }
    }
}

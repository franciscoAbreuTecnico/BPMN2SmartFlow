package com.rdf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
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
 *        - smartFlow.ttl      (SmartFlow schema + sf:has_nextNode chain)
 *        - instanceTtl        (RML generated SmartFlow individuals)
 *        - bpmn.ttl           (BBO/BPMN ontology, including bbo:SequenceFlow, bbo:ExclusiveGateway, etc.)
 *        - mapping.ttl        (SmartFlow↔BPMN alignments)
 *   2) Merging them into one OWLOntology.
 *   3) Running HermiT (precomputeInferences) so that every sf:has_nextNode(x,y) is materialized.
 *   4) Programmatically creating:
 *        - If x has exactly one inferred sf:has_nextNode, a direct bbo:SequenceFlow x→y.
 *        - If x has two or more inferred sf:has_nextNode targets, a bbo:ExclusiveGateway_x plus:
 *            – a SequenceFlow x→ExclusiveGateway_x
 *            – one SequenceFlow ExclusiveGateway_x→each y
 *   5) Assigning each new SequenceFlow and ExclusiveGateway a bbo:id and bbo:name.
 *   6) Providing DLquery helpers (getSubClasses, getInstances, etc.).
 *   7) Providing "inferred properties by class" methods.
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

    private final OWLObjectProperty hasActionProp;
    private final OWLObjectProperty hasButtonProp;
    private final OWLObjectProperty hasActionProcessorProp;
    private final OWLObjectProperty hasAssociationProp;
    private final OWLObjectProperty hasHandlerProp;
    private final OWLObjectProperty hasAssociatedFormProp;
    private final OWLDataProperty   sfIdProp;

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
        this.hasActionProp          = df.getOWLObjectProperty(IRI.create(SF_NS + "has_action"));
        this.hasButtonProp          = df.getOWLObjectProperty(IRI.create(SF_NS + "has_button"));
        this.hasActionProcessorProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_actionProcessor"));
        this.hasAssociationProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_association"));
        this.hasHandlerProp        = df.getOWLObjectProperty(IRI.create(SF_NS + "has_handler"));
        this.hasAssociatedFormProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_associatedForm"));
        this.sfIdProp              = df.getOWLDataProperty(  IRI.create(SF_NS + "sfId"));

        // 6) Programmatically create bbo:SequenceFlow (and bbo:ExclusiveGateway if needed) for every inferred (x sf:has_nextNode y)
        materializeSequenceFlowsFromNextNode();
        assignQueueToTerminalNodes();
        assignQueueToChildElements();

        // 7) Prepare DLQueryEngine for Manchester syntax queries
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

            writer.newLine();

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
    //  DLQuery Helper Methods
    // --------------------------------------------------------

    /**
     * Return all individuals (OWLNamedIndividual) that satisfy the given Manchester syntax class expression.
     * @param classExpressionString e.g. "ActionNode"
     * @param direct                true = only direct instances; false = include subclass instances.
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
    
public final void materializeSequenceFlowsFromNextNode() {
    OWLDataFactory df = manager.getOWLDataFactory();

    // SmartFlow properties
    OWLObjectProperty hasButtonProp          = df.getOWLObjectProperty(IRI.create(SF_NS + "has_button"));
    OWLObjectProperty hasHandlerProp         = df.getOWLObjectProperty(IRI.create(SF_NS + "has_handler"));
    OWLObjectProperty hasHandlersActionProp  = df.getOWLObjectProperty(IRI.create(SF_NS + "has_handlersAction"));
    OWLObjectProperty hasActionProp          = df.getOWLObjectProperty(IRI.create(SF_NS + "has_action"));
    OWLObjectProperty transitionsToProp      = df.getOWLObjectProperty(IRI.create(SF_NS + "has_transitionsTo"));
    OWLObjectProperty hasActionProcessorProp = df.getOWLObjectProperty(IRI.create(SF_NS + "has_actionProcessor"));
    OWLObjectProperty hasApplyOnProp         = df.getOWLObjectProperty(IRI.create(SF_NS + "has_applyOn"));
    OWLObjectProperty queueProp              = df.getOWLObjectProperty(IRI.create(SF_NS + "has_queue"));

    // BPMN classes & props
    OWLClass          seqFlowCls = df.getOWLClass(IRI.create(BPMN_NS + "SequenceFlow"));
    OWLObjectProperty srcProp     = df.getOWLObjectProperty(IRI.create(BPMN_NS + "has_sourceRef"));
    OWLObjectProperty tgtProp     = df.getOWLObjectProperty(IRI.create(BPMN_NS + "has_targetRef"));
    OWLDataProperty   idProp      = df.getOWLDataProperty(IRI.create(BPMN_NS + "id"));
    OWLDataProperty   nameProp    = df.getOWLDataProperty(IRI.create(BPMN_NS + "name"));
    OWLClass          xorGwCls    = df.getOWLClass(IRI.create(BPMN_NS + "ExclusiveGateway"));

    // 1) emit one SequenceFlow from→to
    BiConsumer<OWLNamedIndividual,OWLNamedIndividual> emitFlow = (from,to) -> {
        String seqId = from.getIRI().getFragment() + "_to_" + to.getIRI().getFragment();
        OWLNamedIndividual seq = df.getOWLNamedIndividual(IRI.create(BPMN_NS + seqId));
        manager.addAxiom(mergedOntology, df.getOWLClassAssertionAxiom(seqFlowCls, seq));
        manager.addAxiom(mergedOntology, df.getOWLObjectPropertyAssertionAxiom(srcProp, seq, from));
        manager.addAxiom(mergedOntology, df.getOWLObjectPropertyAssertionAxiom(tgtProp, seq, to));
        manager.addAxiom(mergedOntology, df.getOWLDataPropertyAssertionAxiom(idProp, seq, df.getOWLLiteral(seqId)));
        manager.addAxiom(mergedOntology, df.getOWLDataPropertyAssertionAxiom(nameProp, seq, df.getOWLLiteral("")));
    };

    // 2) create XOR gateway named by frag, copying subj’s queues
    BiFunction<String,OWLNamedIndividual,OWLNamedIndividual> makeGateway = (frag, subj) -> {
        String gwId = "ExclusiveGateway_" + frag;
        OWLNamedIndividual gw = df.getOWLNamedIndividual(IRI.create(BPMN_NS + gwId));
        manager.addAxiom(mergedOntology, df.getOWLClassAssertionAxiom(xorGwCls, gw));
        manager.addAxiom(mergedOntology, df.getOWLDataPropertyAssertionAxiom(idProp, gw, df.getOWLLiteral(gwId)));
        manager.addAxiom(mergedOntology, df.getOWLDataPropertyAssertionAxiom(nameProp, gw, df.getOWLLiteral("XOR for " + frag)));
        // copy queues
        reasoner.getObjectPropertyValues(subj, queueProp)
                .entities()
                .forEach(q -> manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(queueProp, gw, q)));
        return gw;
    };

    // 3) main loop
    for (OWLNamedIndividual subj : mergedOntology.getIndividualsInSignature()) {
        // 3a) collect all Actions
        List<OWLNamedIndividual> actions = reasoner
            .getObjectPropertyValues(subj, hasActionProp)
            .entities().collect(Collectors.toList());
        if (actions.isEmpty()) continue;

        // 3b) subject level XOR if >1 Action
        OWLNamedIndividual entry = subj;
        if (actions.size() > 1) {
            entry = makeGateway.apply(subj.getIRI().getFragment(), subj);
            emitFlow.accept(subj, entry);
        }

        // 3c) map Action → Processors
        Map<OWLNamedIndividual,List<OWLNamedIndividual>> actionToProcs = new HashMap<>();
        for (OWLNamedIndividual proc : reasoner
                .getObjectPropertyValues(subj, hasActionProcessorProp)
                .entities().collect(Collectors.toList())) {
            manager.addAxiom(mergedOntology,
                df.getOWLObjectPropertyAssertionAxiom(hasActionProcessorProp, subj, proc));
            Set<OWLNamedIndividual> applies = reasoner
                .getObjectPropertyValues(proc, hasApplyOnProp)
                .entities().collect(Collectors.toSet());
            if (applies.isEmpty()) {
                actions.forEach(a ->
                    actionToProcs.computeIfAbsent(a, k->new ArrayList<>()).add(proc));
            } else {
                applies.forEach(a ->
                    actionToProcs.computeIfAbsent(a, k->new ArrayList<>()).add(proc));
            }
        }

        // 3d) map Action -> Buttons with all Actions rules
        Map<OWLNamedIndividual,List<OWLNamedIndividual>> actionToButtons = new HashMap<>();
        for (OWLNamedIndividual btn : reasoner
                .getObjectPropertyValues(subj, hasButtonProp)
                .entities().collect(Collectors.toList())) {
            manager.addAxiom(mergedOntology,
                df.getOWLObjectPropertyAssertionAxiom(hasButtonProp, subj, btn));

            List<OWLNamedIndividual> handlers = reasoner
                .getObjectPropertyValues(btn, hasHandlerProp)
                .entities().collect(Collectors.toList());
            if (handlers.isEmpty()) {
                // no handler → button→all Actions
                actions.forEach(a ->
                    actionToButtons.computeIfAbsent(a, k->new ArrayList<>()).add(btn));
            } else {
                for (OWLNamedIndividual h : handlers) {
                    manager.addAxiom(mergedOntology,
                        df.getOWLObjectPropertyAssertionAxiom(hasHandlerProp, btn, h));
                    List<OWLNamedIndividual> hActs = reasoner
                        .getObjectPropertyValues(h, hasHandlersActionProp)
                        .entities().collect(Collectors.toList());
                    if (hActs.isEmpty()) {
                        // handler w/o handlersAction → button→all Actions
                        actions.forEach(a ->
                            actionToButtons.computeIfAbsent(a, k->new ArrayList<>()).add(btn));
                    } else {
                        for (OWLNamedIndividual act : hActs) {
                            manager.addAxiom(mergedOntology,
                                df.getOWLObjectPropertyAssertionAxiom(hasHandlersActionProp, h, act));
                            actionToButtons
                                .computeIfAbsent(act, k->new ArrayList<>())
                                .add(btn);
                        }
                    }
                }
            }
        }

        // 3e) invert to Button → Actions
        Map<OWLNamedIndividual,List<OWLNamedIndividual>> buttonToActions = new HashMap<>();
        actionToButtons.forEach((act, btns) -> {
            for (OWLNamedIndividual btn : btns) {
                buttonToActions
                    .computeIfAbsent(btn, k->new ArrayList<>())
                    .add(act);
            }
        });

        // 4) emit Button driven branches
        for (Map.Entry<OWLNamedIndividual,List<OWLNamedIndividual>> e : buttonToActions.entrySet()) {
            OWLNamedIndividual btn  = e.getKey();
            List<OWLNamedIndividual> acts = e.getValue();

            // subj/gateway -> button
            emitFlow.accept(entry, btn);

            // button level XOR if needed
            OWLNamedIndividual btnEntry = btn;
            if (acts.size() > 1) {
                btnEntry = makeGateway.apply(btn.getIRI().getFragment(), subj);
                emitFlow.accept(btn, btnEntry);
            }

            // then each branch to its Action
            for (OWLNamedIndividual action : acts) {
                emitFlow.accept(btnEntry, action);
            }
        }

        // 5) direct Action branches for any not covered by a Button
        Set<OWLNamedIndividual> covered = actionToButtons.keySet();
        for (OWLNamedIndividual action : actions) {
            if (!covered.contains(action)) {
                emitFlow.accept(entry, action);
            }
        }

        // 6) for every Action: hook in processors, then transitions
        for (OWLNamedIndividual action : actions) {
            // re-assert sf:has_action
            manager.addAxiom(mergedOntology,
                df.getOWLObjectPropertyAssertionAxiom(hasActionProp, subj, action));

            // a) hook in ActionProcessor → may need XOR
            List<OWLNamedIndividual> procs = actionToProcs.getOrDefault(action, Collections.emptyList());
            List<OWLNamedIndividual> procSources = new ArrayList<>();
            if (procs.isEmpty()) {
                procSources.add(action);
            } else if (procs.size() == 1) {
                OWLNamedIndividual p = procs.get(0);
                emitFlow.accept(action, p);
                procSources.add(p);
            } else {
                OWLNamedIndividual pGw = makeGateway.apply(action.getIRI().getFragment(), subj);
                emitFlow.accept(action, pGw);
                for (OWLNamedIndividual p : procs) {
                    emitFlow.accept(pGw, p);
                    procSources.add(p);
                }
            }

            // b) finally route each source → nextNode(s) with possible XOR
            Set<OWLNamedIndividual> nexts = reasoner
                .getObjectPropertyValues(action, transitionsToProp)
                .entities().collect(Collectors.toSet());
            nexts.forEach(t ->
                manager.addAxiom(mergedOntology,
                    df.getOWLObjectPropertyAssertionAxiom(transitionsToProp, action, t)));

            for (OWLNamedIndividual src : procSources) {
                if (nexts.size() == 1) {
                    emitFlow.accept(src, nexts.iterator().next());
                } else if (nexts.size() > 1) {
                    OWLNamedIndividual nGw = makeGateway.apply(src.getIRI().getFragment(), subj);
                    emitFlow.accept(src, nGw);
                    for (OWLNamedIndividual t : nexts) {
                        emitFlow.accept(nGw, t);
                    }
                }
            }
        }
    }

    reasoner.flush();
}




    // --------------------------------------------------------
    //  Getters for inferred nextNode and all sequenceFlow Links
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


    public List<OWLNamedIndividual> getAllLaneIndividuals() {
        // Look up the Lane class from the BPMN namespace
        OWLClass laneClass = df.getOWLClass(IRI.create(BPMN_NS + "Lane"));
        // Ask the reasoner for all instances (including those inferred via subclass)
        NodeSet<OWLNamedIndividual> instances = reasoner.getInstances(laneClass, false);
        // Convert NodeSet to a List<OWLNamedIndividual>
        return instances.entities().collect(Collectors.toList());
    }

    /**
     * Copy each ActionNode’s sf:has_queue values down to its
     * sf:Action, sf:Button and sf:ActionProcessor children.
     */
    private void assignQueueToChildElements() {
        // get all ActionNode individuals
        OWLClass actionNodeCls = df.getOWLClass(IRI.create(SF_NS + "ActionNode"));
        Set<OWLNamedIndividual> nodes =
            reasoner.getInstances(actionNodeCls, /*direct=*/false)
                    .entities().collect(Collectors.toSet());

        for (OWLNamedIndividual node : nodes) {
            // collect the node’s queue targets
            Set<OWLNamedIndividual> queues = mergedOntology
                .getObjectPropertyAssertionAxioms(node).stream()
                .filter(ax -> ax.getProperty().equals(queueProp))
                .map(ax -> ax.getObject().asOWLNamedIndividual())
                .collect(Collectors.toSet());
            if (queues.isEmpty()) continue;

            // 1) copy to each sf:Action
            reasoner.getObjectPropertyValues(node, hasActionProp).entities()
                .forEach(action ->
                    queues.forEach(q ->
                        manager.addAxiom(mergedOntology,
                            df.getOWLObjectPropertyAssertionAxiom(queueProp, action, q)))
                );

            // 2) copy to each sf:Button
            reasoner.getObjectPropertyValues(node, hasButtonProp).entities()
                .forEach(button ->
                    queues.forEach(q ->
                        manager.addAxiom(mergedOntology,
                            df.getOWLObjectPropertyAssertionAxiom(queueProp, button, q)))
                );

            // 3) copy to each sf:ActionProcessor
            reasoner.getObjectPropertyValues(node, hasActionProcessorProp).entities()
                .forEach(proc ->
                    queues.forEach(q ->
                        manager.addAxiom(mergedOntology,
                            df.getOWLObjectPropertyAssertionAxiom(queueProp, proc, q)))
                );
        }
        reasoner.flush();
    }
    
    private void assignQueueToTerminalNodes() {
        // 1) find all ActionNode individuals
        OWLClass actionNodeCls = df.getOWLClass(IRI.create(SF_NS + "StartNode"));
        Set<OWLNamedIndividual> actionNodes =
            reasoner.getInstances(actionNodeCls, /*direct=*/false)
                    .entities().collect(Collectors.toSet());

        if (actionNodes.isEmpty()) {
            // no ActionNodes -> nothing to copy
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

    public void printFlowElements() {
        // Fetch all FlowElement individuals (direct + indirect)
        Set<OWLNamedIndividual> elements = getInstances("FlowElement", false);

        System.out.println("=== BPMN FlowElements ===");
        for (OWLNamedIndividual fe : elements) {
            // Ask HermiT for the direct types of this individual
            NodeSet<OWLClass> typeNodes = reasoner.getTypes(fe, /* direct = */ true);
            Set<OWLClass> types = typeNodes.getFlattened();

            // If there are no explicit types, still print the element
            if (types.isEmpty()) {
                System.out.println(" -> (no explicit class) : " + getShortForm(fe));
            } else {
                // Print one line per class
                for (OWLClass cls : types) {
                    String clsShort = shortFormProvider.getShortForm(cls);
                    System.out.println(" -> " + clsShort + " : " + getShortForm(fe));
                }
            }
        }
    }

    /**
     * Print all bbo:SequenceFlow individuals along with their has_sourceRef, has_targetRef, id, and name.
     * Format:
     *   SequenceFlow_…  has_sourceRef=x  has_targetRef=y  id="…"  name="…"
     */
    public void printSequenceFlows() {
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
    //  Public getters
    // --------------------------------------------------------

    /**
     * If buttonInd →has_handler→ handler →has_associatedForm→ form,
     * returns that form’s sfId literal; else null.
     */
    public String getFormKeyForButton(OWLNamedIndividual buttonInd) {
        // 1) find its handler(s)
        for (OWLNamedIndividual handler : reasoner
                .getObjectPropertyValues(buttonInd, hasHandlerProp)
                .entities().collect(Collectors.toSet())) {

            // 2) find any associated Form(s)
            for (OWLNamedIndividual form : reasoner
                    .getObjectPropertyValues(handler, hasAssociatedFormProp)
                    .entities().collect(Collectors.toSet())) {

                // 3) grab the sfId literal
                return mergedOntology.getDataPropertyAssertionAxioms(form).stream()
                    .filter(ax -> ax.getProperty().equals(sfIdProp))
                    .map(ax -> ax.getObject().getLiteral())
                    .findFirst().orElse(null);
            }
        }
        return null;
    }

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
     * Return the literal value of the given data property (by local name) on this individual,
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

package com.rdf.generator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.Documentation;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import com.rdf.OntologyService;
import com.rdf.exception.BpmnGenerationException;
import com.rdf.layout.CamundaAutoLayoutWithLanes.AutoLayoutEngine;
import com.rdf.layout.CamundaAutoLayoutWithLanes.LayoutConfig;

public class BpmnModelBuilder {

    private final OntologyService ontService;
    private final Map<String, Lane>    laneMap   = new LinkedHashMap<>();
    private final List<Lane>           lanes     = new ArrayList<>();

    public BpmnModelBuilder(OntologyService ontologyService) {
        this.ontService = ontologyService;
    }

    public void generateBpmnModel() throws BpmnModelException, BpmnGenerationException {
        try {
            // Create an empty BpmnModel
            BpmnModelInstance modelInstance = Bpmn.createEmptyModel();

            // Definitions and Execution attributes
            Definitions definitions = setDefinitionsAttributes(modelInstance);

            Process process = createProcess(definitions);

            // Collaboration <=> ID if the process (FlowTemplate name)
            Collaboration collaboration = createElement(definitions, "smart_flow_collaboration", Collaboration.class);
            collaboration.setName("SmartFlow Collaboration"); // optional, if you set name
            attachDocumentation(collaboration, ""); // ibpmCollaboration.getElementDocumentation()); // raw JSON
            definitions.addChildElement(collaboration);

            // For now, it only has 1 Participant/Pool
            Participant participant = createElement(collaboration, "smart_flow_participant", Participant.class);
            participant.setName("Smart Flow");
            participant.setProcess(process);

            instantiateLanes(process);

            instantiateFlowObjects(modelInstance, process);
            
            instantiateConnectingObjects(modelInstance, process);

            validateAndSaveModel(modelInstance, "src/main/resources/output/test.bpmn"); // fileName

        } catch (BpmnGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnGenerationException("Unexpected error during BPMN generation: " + e.getMessage(), e);
        }
    }

    public void instantiateFlowObjects(BpmnModelInstance modelInstance, Process process) {
        // a) Start Event
        ontService.getInstances("ProcessStartEvent", false)
        .forEach(ind -> createBpmnElement(process, ind, StartEvent.class, ontService.getDataPropertyValue(ind, "sfName")));

        // b) User Task
        ontService.getInstances("UserTask", false)
        .forEach(ind -> createBpmnElement(process, ind, UserTask.class, ind.getIRI().getFragment()));

        // c) Exclusive Gateway
        ontService.getInstances("ExclusiveGateway", false)
        .forEach(ind -> createBpmnElement(process, ind, ExclusiveGateway.class, ind.getIRI().getFragment()));

        // d) End Event
        ontService.getInstances("EndEvent", false)
        .forEach(ind -> createBpmnElement(process, ind, EndEvent.class, ontService.getDataPropertyValue(ind, "sfName")));

    }

    public void instantiateConnectingObjects(BpmnModelInstance modelInstance, Process process) {
        for (OWLNamedIndividual seqInd : ontService.getAllSequenceFlowIndividuals()) {
            String sfId = seqInd.getIRI().getFragment();
            String sfName = ontService.getDataPropertyValue(seqInd, "name");

            // find sourceRef and targetRef by scanning ABox axioms
            String sourceRef = null, targetRef = null;
            OWLOntology merged = ontService.getMergedOntology();
            for (OWLObjectPropertyAssertionAxiom ax : 
                merged.getObjectPropertyAssertionAxioms(seqInd)) {
                String prop = ax.getProperty().asOWLObjectProperty().getIRI().getFragment();
                String obj  = ax.getObject().asOWLNamedIndividual().getIRI().getFragment();
                if ("has_sourceRef".equals(prop)) sourceRef = obj;
                if ("has_targetRef".equals(prop)) targetRef = obj;
            }

            if (sourceRef == null || targetRef == null) {
                throw new IllegalStateException("SequenceFlow " + sfId +
                    " missing sourceRef or targetRef");
            }

            FlowNode source = (FlowNode) modelInstance.getModelElementById(sourceRef);
            FlowNode target = (FlowNode) modelInstance.getModelElementById(targetRef);
            if (source == null || target == null) {
                throw new IllegalStateException("Could not find FlowNode for "
                    + sourceRef + " or " + targetRef);
            }
            createSequenceFlow(process, source, target, sfId, sfName);
        }
    }

    //////////////////////////////////////
    // 
    /// //////////////////////////////////

    /**
     * Creates a new Process element and adds it to the Definitions.
     *
     * @param definitions The Definitions instance to which the Process will be added.
     * @param processName The name of the process.
     * @return The created Process instance.
     */
    private Definitions setDefinitionsAttributes(BpmnModelInstance modelInstance) {
        
        Definitions definitions = modelInstance.newInstance(Definitions.class);
        definitions.setTargetNamespace("http://bpmn.io/schema/bpmn");
        definitions.setExporter("Camunda Modeler");
        definitions.setExporterVersion("5.29.0");
        definitions.getDomElement().registerNamespace("camunda", "http://camunda.org/schema/1.0/bpmn");

        // Register namespaces
        definitions.getDomElement().registerNamespace("bpmndi", "http://www.omg.org/spec/BPMN/20100524/DI");
        definitions.getDomElement().registerNamespace("di", "http://www.omg.org/spec/DD/20100524/DI");
        definitions.getDomElement().registerNamespace("dc", "http://www.omg.org/spec/DD/20100524/DC");

        // Attach execution platform attributes correctly
        // definitions.setAttributeValueNs("http://camunda.org/schema/modeler/1.0", "executionPlatform", "Camunda Platform");
        // definitions.setAttributeValueNs("http://camunda.org/schema/modeler/1.0", "executionPlatformVersion", "7.22.0");
        
        modelInstance.setDefinitions(definitions);
        return definitions;
    }

    /**
     * Creates a new Process element and adds it to the Definitions.
     *
     * @param definitions The Definitions instance to which the Process will be added.
     * @param ibpmProcess The IBPMProcess instance containing process details.
     * @return The created Process instance.
     */
    private Process createProcess(Definitions definitions) {
        Process process = definitions.getModelInstance().newInstance(Process.class);
        process.setAttributeValue("id", "smart_flow_process", true);
        process.setName("Smart Flow Process");
        definitions.addChildElement(process);

        return process;
    }

    public void instantiateLanes(Process process) {
        
        LaneSet laneSet = createElement(process, "LaneSet_1", LaneSet.class);
        process.getLaneSets().add(laneSet);

        ontService.getInstances("Lane", false)
        .forEach(ind -> {
            String laneId = ind.getIRI().getFragment();
            Lane lane = createElement(laneSet, laneId, Lane.class);
            lane.setName(ontService.getDataPropertyValue(ind, "sfName"));
            laneSet.addChildElement(lane);
            lanes.add(lane);
            laneMap.put(laneId, lane);
        });
    }

    /*
     * Attach documentation to a BPMN element
     * @param element The BPMN element to which the documentation will be attached
     * @param text The documentation text
     * @return The created Documentation element
     * @throws BpmnGenerationException if an error occurs while creating the documentation element
     */
    private void attachDocumentation(BaseElement element, String text) {
        if (text != null && !text.isBlank()) {
            Documentation documentation = element.getModelInstance().newInstance(Documentation.class);
            documentation.setTextContent(text);
            element.addChildElement(documentation);
        }
    } 

    /**
     * Create a BPMN element of the given type, set its id and (possibly overridden) name,
     * add it to the process, and assign it to any swimlane(s) based on sf:has_queue.
     *
     * @param process       the Camunda <process> to add this node to
     * @param ind           the ontology individual (its IRI fragment → element id)
     * @param elementClass  the BPMN element class (StartEvent, UserTask, ExclusiveGateway, EndEvent, etc.)
     * @param defaultName   the fallback name to use if no sfName is found (e.g. "Start Event")
     * @param <T>           a subclass of FlowNode
     * @return              the newly created BPMN element
     */
    private <T extends FlowNode> T createBpmnElement(
        Process process,
        OWLNamedIndividual ind,
        Class<T> elementClass,
        String defaultName) {

        // 1) instantiate the BPMN element and set its id
        String id = ind.getIRI().getFragment();
        T node = createElement(process, id, elementClass);

        // 2) determine the name: for UserTask use sfName if present, otherwise fallback
        String nameToSet = defaultName;
        if (node instanceof UserTask) {
            String sfName = ontService.getDataPropertyValue(ind, "sfName");
            if (sfName != null && !sfName.isBlank()) {
            nameToSet = sfName;
            }
        }
        node.setName(nameToSet);

         // 3) assign this node into any swimlane(s) via sf:has_queue
        Set<OWLNamedIndividual> queues = ontService.getQueuesForElement(ind);
        for (OWLNamedIndividual queueInd : queues) {
            String laneId = queueInd.getIRI().getFragment();
            Lane lane = laneMap.get(laneId);
            if (lane != null) {
            lane.getFlowNodeRefs().add(node);
            }
        }

        return node;
    }

    /*
    * 1. Create new instance of BPMN element
    * 2. Set attributes and child elements of the element instance
    * 3. Add the newly created element instance to the corresponding parent element
    */
    private static <T extends BpmnModelElementInstance> T createElement(BpmnModelElementInstance parentElement, String id, Class<T> elementClass) {
        T element = parentElement.getModelInstance().newInstance(elementClass);
        element.setAttributeValue("id", id, true);
        parentElement.addChildElement(element);
        return element;
    }

    /**
     * Creates a SequenceFlow between two FlowNodes and adds it to the Process.
     *
     * @param process The Process instance to which the SequenceFlow will be added.
     * @param from The source FlowNode of the SequenceFlow.
     * @param to The target FlowNode of the SequenceFlow.
     * @param id The ID of the SequenceFlow.
     * @param actionName The name of the action associated with the SequenceFlow.
     * @return The created SequenceFlow instance.
     */
    private SequenceFlow createSequenceFlow(Process process, FlowNode from, FlowNode to, String id, String name) {
        SequenceFlow sequenceFlow = createElement(process, id, SequenceFlow.class);
        process.addChildElement(sequenceFlow);
        
        sequenceFlow.setSource(from);
        from.getOutgoing().add(sequenceFlow);
        sequenceFlow.setTarget(to);
        to.getIncoming().add(sequenceFlow);

        sequenceFlow.setName(name);

        return sequenceFlow;
    }

        /*
     * Validates the BPMN model and writes it to a file
     * @param modelInstance The BPMN model instance to validate and save
     * @param fileName The name of the file to save the model to
     * @throws BpmnGenerationException if an error occurs during validation or saving
     */
    private void validateAndSaveModel(BpmnModelInstance modelInstance, String fileName) throws BpmnGenerationException {
        try {
            LayoutConfig cfg = new LayoutConfig();
            AutoLayoutEngine ale = new AutoLayoutEngine();
            ale.layout(modelInstance, cfg);
            System.out.println("Auto layout applied to BPMN model.");

            Bpmn.validateModel(modelInstance);
            File file = new File(fileName);
            file.getParentFile().mkdirs();
            Bpmn.writeModelToFile(file, modelInstance);
            System.out.println("BPMN model written to file: " + file.getAbsolutePath());
        } catch (Exception e) {
            throw new BpmnGenerationException("Error saving BPMN model: " + fileName, e);
        }
    }
}   

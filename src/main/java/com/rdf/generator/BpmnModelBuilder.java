package com.rdf.generator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelException;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Association;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.DataObject;
import org.camunda.bpm.model.bpmn.instance.DataObjectReference;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.Documentation;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ScriptTask;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;
import org.camunda.bpm.model.xml.ModelInstance;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLOntology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rdf.exception.BpmnGenerationException;
import com.rdf.extractor.JavaMethodExtractor;
import com.rdf.extractor.enums.PatternType;
import com.rdf.layout.test.LayoutExampleMain.AutoLayoutEngine;
import com.rdf.layout.test.LayoutExampleMain.LayoutConfig;
import com.rdf.service.OntologyService;

public class BpmnModelBuilder {

    private final OntologyService ontService;
    private final Map<String, Lane>    laneMap   = new LinkedHashMap<>();
    private final List<Lane>           lanes     = new ArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Map<PatternType, HashMap<String, JavaMethodExtractor.Location>> methodData;

    public BpmnModelBuilder(
        OntologyService ontologyService,
        Map<PatternType, HashMap<String, JavaMethodExtractor.Location>> methodData
    ) {
        this.ontService   = ontologyService;
        this.methodData   = methodData;
    }
    
    public void generateBpmnModel(String fileName) throws BpmnModelException, BpmnGenerationException {
        try {
            // Create an empty BpmnModel
            BpmnModelInstance modelInstance = Bpmn.createEmptyModel();

            // Definitions and Execution attributes
            Definitions definitions = setDefinitionsAttributes(modelInstance);

            Process process = createProcess(definitions);

            Collaboration collaboration = createFillCollaboration(definitions);

            // For now, it only has 1 Participant/Pool
            Participant participant = createElement(collaboration, "smart_flow_participant", Participant.class);
            participant.setName("Smart Flow");
            participant.setProcess(process);

            instantiateLanes(process);

            instantiateFlowObjects(modelInstance, process);
            
            instantiateConnectingObjects(modelInstance, process);

            validateAndSaveModel(modelInstance, fileName);

        } catch (BpmnGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new BpmnGenerationException("Unexpected error during BPMN generation: " + e.getMessage(), e);
        }
    }

    public void instantiateFlowObjects(BpmnModelInstance modelInstance, Process process) {
        // Start Event
        ontService.getInstances("ProcessStartEvent", false)
        .forEach(ind -> createBpmnElement(process, ind, StartEvent.class, ontService.getDataPropertyValue(ind, "sfName")));

        // User Task
        ontService.getInstances("UserTask", false)
        .forEach(taskInd -> {
        UserTask ut = createBpmnElement(
            process,
            taskInd,
            UserTask.class,
            ontService.getDataPropertyValue(taskInd, "sfName")
        );

        // Reference Camunda Form
        OWLNamedIndividual formInd = ontService.getFormForTask(taskInd);
        if (formInd != null) {
            String formRef = formInd.getIRI().getFragment();
            ut.getDomElement()
            .setAttribute(
                "http://camunda.org/schema/1.0/bpmn",
                "formRef",
                formRef
            );
            ut.getDomElement()
            .setAttribute(
                "http://camunda.org/schema/1.0/bpmn",
                "formRefBinding",
                "latest"
            );
        }
        });

        // Script Task
        instantiateScriptTasks(modelInstance, process);


        // Service Task
        ontService.getInstances("ServiceTask", false)
        .forEach(ind -> createBpmnElement(process, ind, ServiceTask.class, ind.getIRI().getFragment()));

        // Exclusive Gateway
        ontService.getInstances("ExclusiveGateway", false)
        .forEach(ind -> createBpmnElement(process, ind, ExclusiveGateway.class, ind.getIRI().getFragment()));

        // End Event
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

    public Collaboration createFillCollaboration(Definitions definitions) throws BpmnGenerationException {
        OWLNamedIndividual ft = ontService.getInstances("ProcessDefinition", false)
            .stream()
            .findFirst()
            .orElseThrow(() -> new BpmnGenerationException("No ProcessDefinition individual found"));

        String processIdName = ontService.getDataPropertyValue(ft, "sfName");

        Collaboration collaboration = createElement(definitions, processIdName, Collaboration.class);
        collaboration.setName(processIdName);

        attachDocumentation(collaboration, ontService.getDataPropertyValue(ft, "rawJson"));
        definitions.addChildElement(collaboration);
        return collaboration;
    }

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

    public void generateFormDefinitions(Path formsDir) throws IOException {
        // make sure the directory exists
        Files.createDirectories(formsDir);

        // get every Form individual
        ontService.getInstances("Form", false).forEach(formInd -> {
            try {
                String formId = formInd.getIRI().getFragment();

                // build a minimal Camunda form JSON:
                ObjectNode formJson = JSON.createObjectNode();
                formJson.put("key", formId);

                // fields array
                ArrayNode fields = formJson.putArray("fields");
                // assume your OntologyService has something like `getFieldsForForm(...)`
                ontService.getFieldsForForm(formInd).forEach(fieldInd -> {
                    ObjectNode f = fields.addObject();
                    f.put("id",      fieldInd.getIRI().getFragment());
                    f.put("label",   ontService.getDataPropertyValue(fieldInd, "sfName"));
                    // ... any other metadata you need (type, validations, etc)
                });

                // write to disk
                Path out = formsDir.resolve(formId + ".form");
                JSON.writerWithDefaultPrettyPrinter()
                    .writeValue(out.toFile(), formJson);
            }
            catch (IOException e) {
                throw new RuntimeException("Error writing form JSON for " 
                                           + formInd.getIRI().getFragment(), e);
            }
        });
    }

    /**
     * Create all ScriptTask nodes, then add a camunda:property link
     * back to the Java source if we found a matching processor.
     */
    private void instantiateScriptTasks(
        BpmnModelInstance modelInstance,
        Process process
    ) {
        Map<String, JavaMethodExtractor.Location> flowProcessorsMap =
            methodData.getOrDefault(PatternType.FLOW_PROCESSORS, new HashMap<>());

        ontService.getInstances("ScriptTask", false)
        .forEach(ind -> {
            // 1) create the ScriptTask
            ScriptTask st = createBpmnElement(
                process,
                ind,
                ScriptTask.class,
                ontService.getDataPropertyValue(ind, "sfName")
            );

            // 2) find the matching Location by the same name
            String processorsName = ontService.getDataPropertyValue(ind, "sfName");
            JavaMethodExtractor.Location loc = flowProcessorsMap.get(processorsName);
            if (loc == null) {
            return;
            }

            // 3) build the GitLab URL
            int lineNumber = loc.lineNumber;
            String fullPath = loc.getFile().toAbsolutePath().toString().replace("\\", "/");

            String[] submodules = {
            "fenixedu-paper-pusher-ist",
            "fenixedu-paper-pusher-integration"
            };
            String branch  = "main";
            String baseUrl = "https://repo.dsi.tecnico.ulisboa.pt/fenixedu/application/fenixedu-paper-pusher";

            for (String sub : submodules) {
            int idx = fullPath.indexOf(sub + "/");
            if (idx != -1) {
                String relativePath = fullPath.substring(idx);
                String fullUrl = String.format(
                "%s/-/blob/%s/%s#L%d",
                baseUrl, branch, relativePath, lineNumber
                );
                // 4) attach as camunda extension property
                Map<String,String> props = new HashMap<>();
                props.put("LINK_" + lineNumber, fullUrl);
                applyExtensionProperties(props, st);
                break;
            }
            }
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
     * Pushes a Map of camunda extension properties onto a BPMN element.
     */
    private void applyExtensionProperties(
        Map<String,String> props,
        BaseElement targetElement
    ) {
        if (props == null || props.isEmpty()) {
            return;
        }

        ModelInstance modelInstance = targetElement.getModelInstance();
        ExtensionElements extElems = targetElement.getExtensionElements();
        if (extElems == null) {
            extElems = modelInstance.newInstance(ExtensionElements.class);
            targetElement.setExtensionElements(extElems);
        }

        // create <camunda:properties>
        CamundaProperties camProps = modelInstance.newInstance(CamundaProperties.class);
        extElems.addChildElement(camProps);

        for (Map.Entry<String,String> e : props.entrySet()) {
            CamundaProperty prop = modelInstance.newInstance(CamundaProperty.class);
            prop.setCamundaName(e.getKey());
            prop.setCamundaValue(e.getValue());
            camProps.addChildElement(prop);
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
        if (node instanceof UserTask || node instanceof ServiceTask || node instanceof DataObject) {
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

    private void createDataObjectForTask(Process process, UserTask userTask, OWLNamedIndividual fieldInd) {
        // base fragment from ontology individual
        String baseId      = fieldInd.getIRI().getFragment();

        // 1) generate a globally unique ID for the DataObject
        String dataObjId   = ensureUniqueId(process.getModelInstance(), baseId);
        DataObject dataObj = createElement(process, dataObjId, DataObject.class);
        String name        = ontService.getDataPropertyValue(fieldInd, "sfName");
        dataObj.setName(name != null ? name : dataObjId);

        // 2) generate a globally unique ID for the DataObjectReference
        String refBase     = dataObjId + "_Ref";
        String refId       = ensureUniqueId(process.getModelInstance(), refBase);
        DataObjectReference ref = createElement(process, refId, DataObjectReference.class);
        ref.setDataObject(dataObj);
        ref.setName(dataObj.getName());

        // 3) add both to the process
        process.addChildElement(dataObj);
        process.addChildElement(ref);

        // 4) associate back to the UserTask
        createDataAssociation(process, userTask, ref);
    }

    private void createDataAssociation(Process process, UserTask userTask, DataObjectReference dataObjectRef) {
        process.addChildElement(dataObjectRef);
    
        Association association = createElement(process, userTask.getId() + "_to_" + dataObjectRef.getId(), Association.class);
        association.setSource(userTask);
        association.setTarget(dataObjectRef);
        
        association.setAttributeValue("associationDirection", "One", true);  // "One" means an arrow pointing to the target
    
        process.addChildElement(association);
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

            Bpmn.validateModel(modelInstance);
            File file = new File(fileName);
            file.getParentFile().mkdirs();
            Bpmn.writeModelToFile(file, modelInstance);
        } catch (Exception e) {
            throw new BpmnGenerationException("Error saving BPMN model: " + fileName, e);
        }
    }

    /////////////
    // UTIL
    /////////////
    
    private String ensureUniqueId(ModelInstance model, String prefix) {
        String id     = prefix;
        int    index  = 1;
        // model.getModelElementById(id) returns null if no element has that ID
        while (model.getModelElementById(id) != null) {
            id = prefix + "_" + (index++);
        }
        return id;
    }
}   


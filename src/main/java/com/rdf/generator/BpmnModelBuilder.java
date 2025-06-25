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
    Files.createDirectories(formsDir);

    for (OWLNamedIndividual formInd : ontService.getInstances("Form", false)) {
        String formId = formInd.getIRI().getFragment();
        System.out.println("Generating form definition for: " + formId);

        ObjectNode formJson = JSON.createObjectNode();
        formJson.put("id", "Form_" + formId);
        formJson.put("type", "default");
        formJson.put("schemaVersion", 18);

        // Exporter/platform info
        ObjectNode exporter = formJson.putObject("exporter");
        exporter.put("name", "Camunda Modeler");
        exporter.put("version", "5.36.1");
        formJson.put("executionPlatform", "Camunda Platform");
        formJson.put("executionPlatformVersion", "7.23.0");

        ArrayNode components = formJson.putArray("components");

        // Pages as Groups (top-level)
        for (OWLNamedIndividual pageInd : ontService.getPagesForForm(formInd)) {
            ObjectNode pageGroup = components.addObject();
            pageGroup.put("type", "group");
            pageGroup.put("id", "Page_" + pageInd.getIRI().getFragment());
            pageGroup.put("key", "page_" + pageInd.getIRI().getFragment());

            // Group label: Prefer titleEN, fallback to titlePT, then sfName, then sfId
            String groupLabel =
                ontService.getAnnotationValue(pageInd, "titleEN");
            if (groupLabel == null || groupLabel.isBlank())
                groupLabel = ontService.getAnnotationValue(pageInd, "titlePT");
            if (groupLabel == null || groupLabel.isBlank())
                groupLabel = ontService.getDataPropertyValue(pageInd, "sfName");
            if (groupLabel == null || groupLabel.isBlank())
                groupLabel = pageInd.getIRI().getFragment();
            pageGroup.put("label", groupLabel);
            pageGroup.put("showOutline", true);

            // Optional description for the group
            String desc = ontService.getAnnotationValue(pageInd, "descriptionEN");
            if (desc != null && !desc.isBlank())
                pageGroup.put("description", desc);

            ArrayNode sectionComponents = pageGroup.putArray("components");

            // Sections as groups inside Page
            for (OWLNamedIndividual sectionInd : ontService.getSectionsForPage(pageInd)) {
                ObjectNode sectionGroup = sectionComponents.addObject();
                sectionGroup.put("type", "group");
                sectionGroup.put("id", "Section_" + sectionInd.getIRI().getFragment());
                sectionGroup.put("key", "section_" + sectionInd.getIRI().getFragment());

                // Section label: Prefer titleEN, fallback to titlePT, then sfName, then sfId
                String sectionLabel =
                    ontService.getAnnotationValue(sectionInd, "titleEN");
                if (sectionLabel == null || sectionLabel.isBlank())
                    sectionLabel = ontService.getAnnotationValue(sectionInd, "titlePT");
                if (sectionLabel == null || sectionLabel.isBlank())
                    sectionLabel = ontService.getDataPropertyValue(sectionInd, "sfName");
                if (sectionLabel == null || sectionLabel.isBlank())
                    sectionLabel = sectionInd.getIRI().getFragment();
                sectionGroup.put("label", sectionLabel);
                sectionGroup.put("showOutline", true);

                // Optional section description
                String secDesc = ontService.getAnnotationValue(sectionInd, "descriptionEN");
                if (secDesc != null && !secDesc.isBlank())
                    sectionGroup.put("description", secDesc);

                ArrayNode propertyComponents = sectionGroup.putArray("components");

                // Properties as fields inside Section group
                for (OWLNamedIndividual propInd : ontService.getPropertiesForSection(sectionInd)) {
                    String propertyType = ontService.getDataPropertyValue(propInd, "propertyType");
                    String fieldId = "Field_" + propInd.getIRI().getFragment();
                    String fieldKey = propInd.getIRI().getFragment();

                    // Field label: Prefer sfName, fallback to titleEN, titlePT, sfId
                    String fieldLabel = ontService.getAnnotationValue(propInd, "labelEN");
                    if (fieldLabel == null || fieldLabel.isBlank())
                        fieldLabel = ontService.getAnnotationValue(propInd, "titleEN");
                    if (fieldLabel == null || fieldLabel.isBlank())
                        fieldLabel = ontService.getDataPropertyValue(propInd, "sfName");
                    if (fieldLabel == null || fieldLabel.isBlank())
                        fieldLabel = fieldKey;

                    // Description
                    String fieldDesc = ontService.getAnnotationValue(propInd, "descriptionEN");
                    if (fieldDesc == null || fieldDesc.isBlank())
                        fieldDesc = ontService.getAnnotationValue(propInd, "descriptionPT");

                    // Required/readOnly
                    boolean isRequired = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "isRequired"));
                    boolean isReadOnly = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "readOnly"));

                    switch (propertyType) {
                        case "Boolean" -> {
                            ObjectNode radioField = propertyComponents.addObject();
                            radioField.put("type", "radio");
                            radioField.put("id", fieldId);
                            radioField.put("key", fieldKey);
                            radioField.put("label", fieldLabel);
                            if (fieldDesc != null && !fieldDesc.isBlank())
                                radioField.put("description", fieldDesc);
                            radioField.put("required", isRequired);
                            radioField.put("readOnly", isReadOnly);

                            // Boolean radio group: 1 = true, 0 = false
                            ArrayNode values = radioField.putArray("values");
                            ObjectNode yesValue = values.addObject();
                            String yesLabel = ontService.getDataPropertyValue(propInd, "labelYesEN");
                            if (yesLabel == null || yesLabel.isBlank())
                                yesLabel = ontService.getDataPropertyValue(propInd, "labelYesPT");
                            yesValue.put("label", yesLabel != null ? yesLabel : "Yes");
                            yesValue.put("value", 1);

                            ObjectNode noValue = values.addObject();
                            String noLabel = ontService.getDataPropertyValue(propInd, "labelNoEN");
                            if (noLabel == null || noLabel.isBlank())
                                noLabel = ontService.getDataPropertyValue(propInd, "labelNoPT");
                            noValue.put("label", noLabel != null ? noLabel : "No");
                            noValue.put("value", 0);
                        }
                        case "File" -> {
                            ObjectNode fileField = propertyComponents.addObject();
                            fileField.put("type", "filepicker");
                            fileField.put("id", fieldId);
                            fileField.put("key", fieldKey);
                            fileField.put("label", fieldLabel);
                            if (fieldDesc != null && !fieldDesc.isBlank())
                                fileField.put("description", fieldDesc);
                            fileField.put("required", isRequired);
                            fileField.put("readOnly", isReadOnly);

                            String allowedTypes = ontService.getDataPropertyValue(propInd, "allowedFileType");
                            if (allowedTypes != null && !allowedTypes.isBlank())
                                fileField.put("accept", allowedTypes);
                            String maxSize = ontService.getDataPropertyValue(propInd, "maxSizeMB");
                            if (maxSize != null && !maxSize.isBlank())
                                fileField.put("maxFileSize", maxSize + "MB");
                        }
                        case "Text" -> {
                            ObjectNode textField = propertyComponents.addObject();
                            textField.put("type", "text");
                            textField.put("id", fieldId);
                            textField.put("key", fieldKey);
                            textField.put("label", fieldLabel);
                            textField.put("text", fieldLabel);
                            if (fieldDesc != null && !fieldDesc.isBlank()) textField.put("description", fieldDesc);
                            textField.put("required", isRequired);
                            textField.put("readOnly", isReadOnly);
                        }
                        case "LocalizedText" -> {
                            List<String> locales = ontService.getDataPropertyValues(propInd, "locales");
                            // Fallback to en-GB if no locales
                            if (locales == null || locales.isEmpty()) locales = List.of("en-GB");
                            boolean multiline = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "multiline"));

                            for (String locale : locales) {
                                ObjectNode localeField = propertyComponents.addObject();
                                localeField.put("type", multiline ? "textarea" : "text");
                                localeField.put("id", fieldId + "_" + locale);
                                localeField.put("key", fieldKey + "_" + locale);

                                // Choose appropriate label per locale
                                String localizedLabel = fieldLabel;
                                if (locale.equals("pt-PT")) {
                                    String labelPT = ontService.getAnnotationValue(propInd, "labelPT");
                                    if (labelPT != null && !labelPT.isBlank()) localizedLabel = labelPT;
                                }
                                if (locale.equals("en-GB")) {
                                    String labelEN = ontService.getAnnotationValue(propInd, "labelEN");
                                    if (labelEN != null && !labelEN.isBlank()) localizedLabel = labelEN;
                                }
                                localeField.put("label", localizedLabel + " (" + locale + ")");
                                if (fieldDesc != null && !fieldDesc.isBlank())
                                    localeField.put("description", fieldDesc + " [" + locale + "]");
                                localeField.put("required", isRequired);
                                localeField.put("readOnly", isReadOnly);
                            }
                        }
                        case "Quantity" -> {
                            ObjectNode numberField = propertyComponents.addObject();
                            numberField.put("type", "number");
                            numberField.put("id", fieldId);
                            numberField.put("key", fieldKey);

                            // If labelEN/PT present, pick one as primary, or handle i18n at a higher level.
                            String labelEN = ontService.getAnnotationValue(propInd, "labelEN");
                            String labelPT = ontService.getAnnotationValue(propInd, "labelPT");
                            // Choose label: for now, just use EN, fallback PT, fallback fieldKey
                            numberField.put("label", labelEN != null ? labelEN : (labelPT != null ? labelPT : fieldKey));

                            numberField.put("required", isRequired);
                            numberField.put("readOnly", isReadOnly);

                            // Min/Max/Step (as strings, under validate and increment)
                            String minVal = ontService.getDataPropertyValue(propInd, "minValue");
                            String maxVal = ontService.getDataPropertyValue(propInd, "maxValue");
                            String stepVal = ontService.getDataPropertyValue(propInd, "stepValue");
                            ObjectNode validate = numberField.putObject("validate");
                            if (minVal != null && !minVal.isBlank()) validate.put("min", minVal);
                            if (maxVal != null && !maxVal.isBlank()) validate.put("max", maxVal);

                            if (stepVal != null && !stepVal.isBlank()) numberField.put("increment", stepVal);

                            // decimalDigits is optional, but you can support it
                            String decimalDigits = ontService.getDataPropertyValue(propInd, "decimalDigits");
                            if (decimalDigits != null && !decimalDigits.isBlank()) {
                                numberField.put("decimalDigits", Integer.parseInt(decimalDigits));
                            }

                            // Default value (optional)
                            String defaultValue = ontService.getDataPropertyValue(propInd, "defaultValue");
                            if (defaultValue != null && !defaultValue.isBlank()) numberField.put("defaultValue", defaultValue);
                        }
                        case "Numeric" -> {
                            // TODO - only appears in input/outcome Forms
                        }
                        case "Select" -> {
                            ObjectNode selectField = propertyComponents.addObject();
                            selectField.put("type", "select");
                            selectField.put("id", fieldId);
                            selectField.put("key", fieldKey);
                            selectField.put("label", fieldLabel);

                            if (fieldDesc != null && !fieldDesc.isBlank())
                                selectField.put("description", fieldDesc);

                            selectField.put("required", isRequired);
                            selectField.put("readOnly", isReadOnly);

                            boolean multiple = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "multiple"));
                            boolean collapse = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "collapse"));
                            boolean allowOther = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "allowOther"));

                            selectField.put("multiple", multiple);
                            selectField.put("collapse", collapse);
                            selectField.put("allowOther", allowOther);

                            ArrayNode valuesArr = selectField.putArray("values");
                            for (OWLNamedIndividual optionInd : ontService.getOptionsForProperty(propInd)) {
                                ObjectNode valueObj = valuesArr.addObject();
                                String value = ontService.getDataPropertyValue(optionInd, "value");
                                valueObj.put("value", value);

                                // Option label (can be multilingual or simple string)
                                ObjectNode labelObj = valueObj.putObject("label");
                                String labelEN = ontService.getAnnotationValue(optionInd, "labelEN");
                                String labelPT = ontService.getAnnotationValue(optionInd, "labelPT");
                                if (labelEN != null) labelObj.put("en-GB", labelEN);
                                if (labelPT != null) labelObj.put("pt-PT", labelPT);
                            }
                        }
                        case "AsyncSelect" -> {
                            ObjectNode asyncSelectField = propertyComponents.addObject();
                            asyncSelectField.put("type", "select");
                            asyncSelectField.put("id", fieldId);
                            asyncSelectField.put("key", fieldKey);
                            asyncSelectField.put("label", fieldLabel);
                            if (fieldDesc != null && !fieldDesc.isBlank())
                                asyncSelectField.put("description", fieldDesc);
                            asyncSelectField.put("required", isRequired);
                            asyncSelectField.put("readOnly", isReadOnly);

                            // Simulate a static option showing the optionsProvider value
                            String optionsProvider = ontService.getDataPropertyValue(propInd, "optionsProvider");
                            ArrayNode valuesArr = asyncSelectField.putArray("values");
                            if (optionsProvider != null && !optionsProvider.isBlank()) {
                                ObjectNode valueObj = valuesArr.addObject();
                                valueObj.put("value", optionsProvider);
                                ObjectNode labelObj = valueObj.putObject("label");
                                labelObj.put("en-GB", optionsProvider);
                                labelObj.put("pt-PT", optionsProvider);
                            } else {
                                ObjectNode valueObj = valuesArr.addObject();
                                valueObj.put("value", "REMOTE_VALUES");
                                ObjectNode labelObj = valueObj.putObject("label");
                                labelObj.put("en-GB", "Remote values");
                                labelObj.put("pt-PT", "Valores remotos");
                            }
                        }
                        case "DateTime" -> {
                            ObjectNode dtField = propertyComponents.addObject();
                            dtField.put("type", "datetime");
                            dtField.put("subtype", "datetime");
                            dtField.put("id", fieldId);
                            dtField.put("key", fieldKey);

                            String dateLabel = ontService.getDataPropertyValue(propInd, "dateLabelEN");
                            if (dateLabel == null || dateLabel.isBlank()) dateLabel = "Date";
                            dtField.put("dateLabel", dateLabel);

                            String timeLabel = ontService.getDataPropertyValue(propInd, "timeLabelEN");
                            if (timeLabel == null || timeLabel.isBlank()) timeLabel = "Time";
                            dtField.put("timeLabel", timeLabel);

                            if (fieldDesc != null && !fieldDesc.isBlank()) {
                                dtField.put("description", fieldDesc);
                            }

                            String timeSerializingFormat = ontService.getDataPropertyValue(propInd, "timeSerializingFormat");
                            dtField.put("timeSerializingFormat", (timeSerializingFormat != null && !timeSerializingFormat.isBlank()) ? timeSerializingFormat : "utc_offset");

                            String timeInterval = ontService.getDataPropertyValue(propInd, "timeInterval");
                            dtField.put("timeInterval", (timeInterval != null && !timeInterval.isBlank()) ? Integer.valueOf(timeInterval) : 15);

                            String use24h = "true";
                            dtField.put("use24h", Boolean.valueOf(use24h));

                            boolean showDate = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "date"));
                            boolean showTime = Boolean.parseBoolean(ontService.getDataPropertyValue(propInd, "time"));
                            dtField.put("date", showDate);
                            dtField.put("time", showTime);

                            ObjectNode layout = dtField.putObject("layout");
                            layout.put("row", "Row_" + fieldId);
                            layout.putNull("columns");

                            ObjectNode validate = dtField.putObject("validate");
                            validate.put("required", isRequired);
                        }
                        case "Array" -> {
                            // TODO
                        }
                        case "Composite" -> {
                            // TODO
                        }
                        default -> {
                            ObjectNode stringField = propertyComponents.addObject();
                            stringField.put("type", "text");
                            stringField.put("id", fieldId);
                            stringField.put("key", fieldKey);
                            stringField.put("label", fieldLabel);
                            if (fieldDesc != null && !fieldDesc.isBlank())
                                stringField.put("description", fieldDesc);
                            stringField.put("required", isRequired);
                            stringField.put("readOnly", isReadOnly);
                        }
                    }
                }
            }
        }

        Path out = formsDir.resolve(formId + ".form");
        System.out.println("Writing form definition to: " + out);
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), formJson);
    }
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
        while (model.getModelElementById(id) != null) {
            id = prefix + "_" + (index++);
        }
        return id;
    }

    public boolean hasFormsToGenerate() {
        try {
            return !ontService.getInstances("Form", false).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}

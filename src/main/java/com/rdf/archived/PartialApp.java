package com.rdf.archived;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdf.OntologyService;
import com.rdf.generator.BpmnModelBuilder;
import com.rdf.util.JsonFlattener;

import be.ugent.rml.cli.Main;

public class PartialApp {
    /**
     * args:
     * 0: input JSON file (e.g., src/main/resources/jsons/MarriageLeaveFlow_Flow.json)
     * 1: RML template file with placeholder {{JSON_SOURCE}}
     * 2: output RDF file
     * 3: BPMN ontology TTL
     * 4: mapping OWL TTL
     */

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // Hardcoded path for the input SmartFlow JSON
        String inputJson = "src/main/resources/jsons/smartFlow/MarriageLeave_Flow.json";
        processFile(inputJson);
    }

    private static void processFile(String inputJson) throws Exception {
        String fileName = Paths.get(inputJson).getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        // Default resources
        String rmlTemplate    = "src/main/resources/rml/flowTemplate.rml.ttl";
        String outputRdfDir   = "src/main/resources/output/instances/";
        String smartFlowTTL   = "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl        = "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl     = "src/main/resources/ontologies/mapping.ttl";

        // Quick JSON validity check and extract flowTemplate
        JsonNode root = MAPPER.readTree(Paths.get(inputJson).toFile());
        JsonNode flowTemplate = root.get("flowTemplate");
        if (flowTemplate == null || !flowTemplate.isObject()) {
            System.out.println("Invalid or missing flowTemplate in " + inputJson);
            return;
        }
        JsonNode config = flowTemplate.get("config");
        JsonNode actionNodes = (config != null) ? config.get("actionNodes") : null;
        if (actionNodes == null || !actionNodes.isArray() || actionNodes.size() == 0) {
            System.out.println("No actionNodes found or empty in " + inputJson);
            return;
        }

        // Prepare RML mapping
        Path template = Paths.get(rmlTemplate);
        String transfJson = JsonFlattener.convert(inputJson);
        String mappingTurtle = Files.readString(template);
        mappingTurtle = mappingTurtle.replace("{{JSON_SOURCE}}", transfJson);
        Path tempMapping = Paths.get("target/temp-mapping-" + baseName + ".ttl");
        Files.createDirectories(tempMapping.getParent());
        Files.writeString(tempMapping, mappingTurtle);

        System.out.println("RML mapping template written to: " + tempMapping.toAbsolutePath());

        // Run RMLMapper
        String outputRdf = outputRdfDir + baseName + "_individuals.ttl";
        Main.main(new String[] {"-m", tempMapping.toString(), "-o", outputRdf, "-s", "Turtle"});
        System.out.println("RDF output written to: " + outputRdf);

        // OWLAPI + Reasoning
        OntologyService ontologyService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );
        BpmnModelBuilder builder = new BpmnModelBuilder(ontologyService);

        // Generate BPMN model
        String bpmnOutputPath = "src/main/resources/output/bpmn/" + baseName + ".bpmn";
        builder.generateBpmnModel(bpmnOutputPath);
        System.out.println("BPMN model written to: " + bpmnOutputPath);

        // Save merged ontology
        try {
            Path mergedOutput = Paths.get("src/main/resources/output/merged/" + baseName + ".ttl");
            ontologyService.saveMergedOntologyAsTurtle(mergedOutput);
            System.out.println("Merged ontology written to: " + mergedOutput.toAbsolutePath());
        } catch (IOException | OWLOntologyStorageException e) {
            e.printStackTrace();
        }
    }
}
package com.rdf.archived;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rdf.OntologyService;
import com.rdf.generator.BpmnModelBuilder;
import com.rdf.util.JsonFlattener;

import be.ugent.rml.cli.Main;

public class PartialApp {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    
    private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/tests/simple_gateway_test.json");
    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/ScholarshipContractFormAndFlow.json");
    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/SabbaticalLeave.json");
    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/MarriageLeave.json");

    /**
     * Processes the hard-coded JSON file: splits into Flow and Request variants,
     * then processes the Flow through the RDF + BPMN pipeline.
     *
     * args (optional):
     *  0: RML template file with placeholder {{JSON_SOURCE}}
     *  1: output RDF folder
     *  2: smartFlow TTL ontology path
     *  3: BPMN ontology TTL path
     *  4: mapping OWL TTL path
     */
    public static void main(String[] args) throws Exception {
        Path jsonFile = INPUT_JSON;
        Path parentDir = jsonFile.getParent();
        Path flowDir = parentDir.resolve("smartFlow");
        Path requestDir = parentDir.resolve("smartForm");
        Files.createDirectories(flowDir);
        Files.createDirectories(requestDir);

        processJsonAndFlowSingle(jsonFile, flowDir, requestDir, args);
    }

    private static void processJsonAndFlowSingle(Path jsonFile, Path flowDir, Path requestDir, String[] args) throws Exception {
        String fileName = jsonFile.getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        JsonNode root = MAPPER.readTree(jsonFile.toFile());
        if (!root.has("flowTemplate")) {
            System.out.println("Skipping file " + fileName + ": missing flowTemplate");
            return;
        }

        // Extract Flow
        JsonNode flowNode = root.get("flowTemplate");
        ObjectNode flowRoot = MAPPER.createObjectNode();
        flowRoot.set("flowTemplate", flowNode);
        Path outFlow = flowDir.resolve(baseName + "_Flow.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFlow.toFile(), flowRoot);

        // Extract Request
        ObjectNode requestRoot = (ObjectNode) root.deepCopy();
        requestRoot.remove("flowTemplate");
        Path outRequest = requestDir.resolve(baseName + "_Request.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outRequest.toFile(), requestRoot);

        // Process Flow
        System.out.println("-> processing flow: " + outFlow.getFileName());
        processFile(outFlow.toString(), args);
        System.out.println();
    }

    private static void processFile(String inputJson, String[] args) throws Exception {
        String fileName = Paths.get(inputJson).getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        // Unpack other args or use defaults
        String rmlTemplate    = args.length > 0 ? args[0] : "src/main/resources/rml/flowTemplate.rml.ttl";
        String outputRdfDir   = args.length > 1 ? args[1] : "src/main/resources/output/instances/";
        String smartFlowTTL   = args.length > 2 ? args[2] : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl        = args.length > 3 ? args[3] : "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl     = args.length > 4 ? args[4] : "src/main/resources/ontologies/mapping.ttl";

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
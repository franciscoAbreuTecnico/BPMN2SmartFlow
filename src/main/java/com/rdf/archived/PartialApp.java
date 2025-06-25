package com.rdf.archived;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rdf.extractor.JavaMethodExtractor;
import com.rdf.extractor.enums.PatternType;
import com.rdf.generator.BpmnModelBuilder;
import com.rdf.service.OntologyService;
import com.rdf.util.JsonFlattener;

import be.ugent.rml.cli.Main;

public class PartialApp {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] SOURCE_PATHS = new String[] {
        "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-integration",
        "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-ist"
    };

    private static final JavaMethodExtractor EXTRACTOR = new JavaMethodExtractor(SOURCE_PATHS);

    private static final Map<PatternType, HashMap<String, JavaMethodExtractor.Location>> METHOD_DATA =
        EXTRACTOR.getResultsMap();

    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/MarriageLeave.json");
    private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/ScholarshipContractFormAndFlow.json");
    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/ScholarshipBoardingFlow.json");
    // private static final Path INPUT_JSON = Paths.get("src/main/resources/jsons/SabbaticalLeave.json");

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
        Path jsonFile  = INPUT_JSON;
        Path parentDir = jsonFile.getParent();
        Path flowDir   = parentDir.resolve("smartFlow");
        Path requestDir= parentDir.resolve("smartForm");

        // make split JSON dirs
        Files.createDirectories(flowDir);
        Files.createDirectories(requestDir);

        // make pipeline output dirs
        Files.createDirectories(Paths.get("src/main/resources/output/instances"));
        Files.createDirectories(Paths.get("src/main/resources/output/bpmn"));
        Files.createDirectories(Paths.get("src/main/resources/output/merged"));
        Files.createDirectories(Paths.get("src/main/resources/output/report"));

        processJsonAndFlowSingle(jsonFile, flowDir, requestDir, args);
    }

    private static void processJsonAndFlowSingle(Path jsonFile, Path flowDir, Path requestDir, String[] args) throws Exception {
        String fileName = jsonFile.getFileName().toString();
        if (!MAPPER.readTree(jsonFile.toFile()).has("flowTemplate")) {
            System.out.println("Skipping file " + fileName + ": missing flowTemplate");
            return;
        }

        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

        // Extract Flow
        JsonNode flowNode = MAPPER.readTree(jsonFile.toFile()).get("flowTemplate");
        ObjectNode flowRoot = MAPPER.createObjectNode().set("flowTemplate", flowNode);
        Path outFlow = flowDir.resolve(baseName + "_Flow.json");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFlow.toFile(), flowRoot);

        // Extract Request
        ObjectNode requestRoot = (ObjectNode) MAPPER.readTree(jsonFile.toFile()).deepCopy();
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
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

        // defaults or args
        String rmlTemplate  = args.length > 0 ? args[0] : "src/main/resources/rml/flowTemplate.rml.ttl";
        String outputRdfDir = args.length > 1 ? args[1] : "src/main/resources/output/instances/";
        String smartFlowTTL = args.length > 2 ? args[2] : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl      = args.length > 3 ? args[3] : "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl   = args.length > 4 ? args[4] : "src/main/resources/ontologies/mapping.ttl";

        // build temporary RML
        Path template     = Paths.get(rmlTemplate);
        String transfJson = JsonFlattener.convert(inputJson);
        String mappingTtl = Files.readString(template).replace("{{JSON_SOURCE}}", transfJson);

        Path tmpMap = Paths.get("target/temp-mapping-" + baseName + ".ttl");
        Files.createDirectories(tmpMap.getParent());
        Files.writeString(tmpMap, mappingTtl);
        System.out.println("RML mapping template written to: " + tmpMap.toAbsolutePath());

        // RMLMapper -> individuals
        String outputRdf = outputRdfDir + baseName + "_individuals.ttl";
        Main.main(new String[] {"-m", tmpMap.toString(), "-o", outputRdf, "-s", "turtle"});
        System.out.println("RDF output written to: " + outputRdf);

        // OWLAPI + reasoning
        OntologyService ontService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );

        BpmnModelBuilder builder = new BpmnModelBuilder(ontService, METHOD_DATA);

        // generate BPMN
        String bpmnOut = "src/main/resources/output/bpmn/" + baseName + ".bpmn";
        Path formsOut = Paths.get("src/main/resources/output/forms");
        Files.createDirectories(formsOut);
        builder.generateFormDefinitions(formsOut);
        builder.generateBpmnModel(bpmnOut);
        System.out.println("BPMN model written to: " + bpmnOut);

        // save merged ontology
        Path mergedOut = Paths.get("src/main/resources/output/merged/" + baseName + ".ttl");
        ontService.saveMergedOntologyAsTurtle(mergedOut);
        System.out.println("Merged ontology written to: " + mergedOut.toAbsolutePath());
    }
}

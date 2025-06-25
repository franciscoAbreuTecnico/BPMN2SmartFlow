package com.rdf;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rdf.extractor.JavaMethodExtractor;
import com.rdf.extractor.enums.PatternType;
import com.rdf.generator.BpmnModelBuilder;
import com.rdf.report.MatrixReport;
import com.rdf.service.OntologyService;
import com.rdf.util.JsonFlattener;

import be.ugent.rml.cli.Main;

public class AppValidate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] SOURCE_PATHS = new String[] {
        "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-integration",
        "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-ist"
    };

    private static final JavaMethodExtractor EXTRACTOR = new JavaMethodExtractor(SOURCE_PATHS);

    private static final Map<PatternType, HashMap<String, JavaMethodExtractor.Location>> METHOD_DATA =
        EXTRACTOR.getResultsMap();

    private static final String[] INPUT_FOLDERS = new String[] {
        "src/main/resources/jsons/humanResources",
        "src/main/resources/jsons/academic",
        "src/main/resources/jsons/scientificCouncil"
    };

    public static void main(String[] args) throws Exception {
        for (String inputFolder : INPUT_FOLDERS) {
            Path jsonDir = Paths.get(inputFolder);
            System.out.println("=== Processing directory: " + jsonDir.toAbsolutePath() + " ===");
            processJsonAndFlow(jsonDir, args);
        }
    }

    private static void processJsonAndFlow(Path jsonDir, String[] args) throws Exception {
        Path instancesDir = Paths.get("src/main/resources/output/instances");
        Path mergedDir    = Paths.get("src/main/resources/output/merged");
        Path reportDir    = Paths.get("src/main/resources/output/report");
        Files.createDirectories(instancesDir);
        Files.createDirectories(mergedDir);
        Files.createDirectories(reportDir);

        Path requestDir = jsonDir.resolve("smartForm");
        Files.createDirectories(requestDir);
        Path smartFlowDir = jsonDir.resolve("smartFlow");
        Files.createDirectories(smartFlowDir);

        // 1) Split JSONs into Flow/Request
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                JsonNode root = MAPPER.readTree(p.toFile());
                if (!root.has("flowTemplate")) {
                    System.out.println("Skipping file " + fileName + ": missing flowTemplate");
                    continue;
                }

                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

                JsonNode flowNode = root.get("flowTemplate");
                ObjectNode flowRoot = MAPPER.createObjectNode().set("flowTemplate", flowNode);
                Path outFlow = smartFlowDir.resolve(baseName + "_Flow.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFlow.toFile(), flowRoot);

                // Extract Request: save inside smartForm subfolder
                ObjectNode requestRoot = (ObjectNode) root.deepCopy();
                requestRoot.remove("flowTemplate");
                Path outRequest = requestDir.resolve(baseName + "_Request.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outRequest.toFile(), requestRoot);
            }
        }

        // 2) Process each generated Flow file through the RDF + BPMN pipeline
        try (DirectoryStream<Path> flowFiles = Files.newDirectoryStream(smartFlowDir, "*_Flow.json")) {
            for (Path flowFile : flowFiles) {
                String flowFileName = flowFile.getFileName().toString();
                try {
                    processFile(flowFile, jsonDir, args, mergedDir, reportDir, instancesDir, smartFlowDir);
                } catch (Exception e) {
                    System.out.println("   Error processing file " + flowFileName + ": " + e.getMessage());
                    e.printStackTrace(System.out);
                }
                System.out.println();
            }
        }
    }

    private static void processFile(
        Path flowFile,
        Path jsonDir,
        String[] args,
        Path mergedDir,
        Path reportDir,
        Path instancesDir,
        Path smartFlowDir
    ) throws Exception {
        String flowFileName = flowFile.getFileName().toString();
        String baseName = flowFileName.substring(0, flowFileName.lastIndexOf("_Flow.json"));

        Path outBpmn         = jsonDir.resolve(baseName + "_Flow.bpmn");
        Path formOutputDir   = jsonDir.resolve(baseName + "_Flow_Forms");

        String convertedPath = JsonFlattener.convert(flowFile.toString()); // e.g. src/main/resources/jsons/humanResources/a_Flow-converted.json

        // Move the -converted.json to the smartFlow dir (with same file name)
        Path converted = Paths.get(convertedPath);
        Path smartFlowConverted = smartFlowDir.resolve(converted.getFileName());
        Files.move(converted, smartFlowConverted, StandardCopyOption.REPLACE_EXISTING);

        // --- 2) RDF mapping pipeline
        String rmlTemplate  = args.length > 0 ? args[0] : "src/main/resources/rml/flowTemplate.rml.ttl";
        String outputRdfDir = instancesDir.toString() + "/";
        String smartFlowTTL = args.length > 2 ? args[2] : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl      = args.length > 3 ? args[3] : "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl   = args.length > 4 ? args[4] : "src/main/resources/ontologies/mapping.ttl";

        Path template = Paths.get(rmlTemplate);
        String transfJson = smartFlowConverted.toString(); // Use the new file as source
        String mappingTurtle = Files.readString(template).replace("{{JSON_SOURCE}}", transfJson);
        Path tempMapping = Paths.get("target/temp-mapping-" + baseName + ".ttl");
        Files.createDirectories(tempMapping.getParent());
        Files.writeString(tempMapping, mappingTurtle);
        
        String outputRdf = outputRdfDir + baseName + "_individuals.ttl";
        Main.main(new String[] {"-m", tempMapping.toString(), "-o", outputRdf, "-s", "turtle"});

        // 4) Reasoning
        OntologyService ontologyService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );

        // 5) Matrix report (central output)
        MatrixReport report = new MatrixReport(ontologyService);
        report.gatherCounts();
        report.appendToCsv(baseName);
        System.out.println("Appended counts to matrix-report.csv under column: " + baseName);

        // 6) Generate BPMN (side-by-side with input)
        BpmnModelBuilder builder = new BpmnModelBuilder(ontologyService, METHOD_DATA);
        builder.generateBpmnModel(outBpmn.toString());
        System.out.println("BPMN model written to: " + outBpmn);

        // 7) Generate Forms IF forms exist
        boolean formsExist = builder.hasFormsToGenerate();
        if (formsExist) {
            Files.createDirectories(formOutputDir);
            builder.generateFormDefinitions(formOutputDir);
            System.out.println("Generated forms in: " + formOutputDir);
        } else {
            System.out.println("No forms generated for: " + baseName);
        }

        // 8) Save the merged ontology (central output)
        Path mergedOutput = mergedDir.resolve(baseName + ".ttl");
        ontologyService.saveMergedOntologyAsTurtle(mergedOutput);
        System.out.println("Merged ontology written to: " + mergedOutput.toAbsolutePath());
    }
}

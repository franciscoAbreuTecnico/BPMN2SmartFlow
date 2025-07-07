package com.rdf;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
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

    private static String[] SOURCE_PATHS;
    private static String[] INPUT_FOLDERS;

    private static JavaMethodExtractor EXTRACTOR;
    private static Map<PatternType, HashMap<String, JavaMethodExtractor.Location>> METHOD_DATA;

    public static void main(String[] args) throws Exception {
        // --- 1) Parse source paths (args[0])
        SOURCE_PATHS = (args.length > 0 && !args[0].isEmpty())
            ? args[0].split(",")
            : new String[] {
                "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-integration",
                "C:\\Users\\franc\\Desktop\\DASI\\fenixedu-paper-pusher\\fenixedu-paper-pusher-ist"
            };

        // --- 2) Parse input folders (args[1])
        INPUT_FOLDERS = (args.length > 1 && !args[1].isEmpty())
            ? args[1].split(",")
            : new String[] {
                "src/main/resources/jsons/humanResources",
                "src/main/resources/jsons/academic",
                "src/main/resources/jsons/scientificCouncil"
            };

        // --- 3) Initialize extractor & method data
        EXTRACTOR   = new JavaMethodExtractor(SOURCE_PATHS);
        METHOD_DATA = EXTRACTOR.getResultsMap();

        // --- 4) Process each folder
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

        Path requestDir   = jsonDir.resolve("smartForm");
        Path smartFlowDir = jsonDir.resolve("smartFlow");
        Files.createDirectories(requestDir);
        Files.createDirectories(smartFlowDir);

        // 1) Split JSONs into Flow / Request
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                JsonNode root = MAPPER.readTree(p.toFile());
                if (!root.has("flowTemplate")) {
                    System.out.println("Skipping file " + fileName + ": missing flowTemplate");
                    continue;
                }
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

                // --- extract flow
                JsonNode flowNode = root.get("flowTemplate");
                ObjectNode flowRoot = MAPPER.createObjectNode().set("flowTemplate", flowNode);
                Path outFlow = smartFlowDir.resolve(baseName + "_Flow.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFlow.toFile(), flowRoot);

                // --- extract request
                ObjectNode requestRoot = (ObjectNode) root.deepCopy();
                requestRoot.remove("flowTemplate");
                Path outRequest = requestDir.resolve(baseName + "_Request.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outRequest.toFile(), requestRoot);
            }
        }

        // 2) RDF + BPMN pipeline on each Flow
        try (DirectoryStream<Path> flowFiles =
                 Files.newDirectoryStream(smartFlowDir, "*_Flow.json")) {
            for (Path flowFile : flowFiles) {
                String flowFileName = flowFile.getFileName().toString();
                try {
                    processFile(
                        flowFile,
                        jsonDir,
                        args,
                        mergedDir,
                        reportDir,
                        instancesDir,
                        smartFlowDir
                    );
                } catch (Exception e) {
                    System.out.println("   Error processing file " + flowFileName + ": " +
                                       e.getMessage());
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
        String baseName     = flowFileName.substring(0, flowFileName.indexOf("_Flow.json"));

        Path outBpmnEN       = jsonDir.resolve(baseName + "_Flow_EN.bpmn");
        Path outBpmnPT       = jsonDir.resolve(baseName + "_Flow_PT.bpmn");
        Path formOutputDir = jsonDir.resolve(baseName + "_Flow_Forms");

        // JSON→flatten→move
        String convertedPath = JsonFlattener.convert(flowFile.toString());
        Path converted       = Paths.get(convertedPath);
        Path smartFlowCopied = smartFlowDir.resolve(converted.getFileName());
        Files.move(converted, smartFlowCopied, StandardCopyOption.REPLACE_EXISTING);

        // --- RDF mapping args
        String rmlTemplate = (args.length > 2 && !args[2].isEmpty())
            ? args[2]
            : "src/main/resources/rml/flowTemplate.rml.ttl";
        String smartFlowTTL = (args.length > 3 && !args[3].isEmpty())
            ? args[3]
            : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl = (args.length > 4 && !args[4].isEmpty())
            ? args[4]
            : "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl = (args.length > 5 && !args[5].isEmpty())
            ? args[5]
            : "src/main/resources/ontologies/mapping.ttl";

        // build temp mapping
        Path template   = Paths.get(rmlTemplate);
        String mapping  = Files.readString(template)
                               .replace("{{JSON_SOURCE}}", smartFlowCopied.toString());
        Path tempMap    = Paths.get("target/temp-mapping-" + baseName + ".ttl");
        Files.createDirectories(tempMap.getParent());
        Files.writeString(tempMap, mapping);

        // run RML
        String outputRdf = instancesDir + "/" + baseName + "_individuals.ttl";
        Main.main(new String[] { "-m", tempMap.toString(),
                                 "-o", outputRdf,
                                 "-s", "turtle" });

        // reasoning & report
        OntologyService ontologyService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );
        MatrixReport report = new MatrixReport(ontologyService);
        report.gatherCounts();
        report.appendToCsv(baseName);
        System.out.println("Appended counts under column: " + baseName);

        // build BPMN
        List<String> parallelmultInst = JsonFlattener.getChangedActionNodeIds();
        BpmnModelBuilder builder = new BpmnModelBuilder(ontologyService, METHOD_DATA, parallelmultInst);
        
        builder.generateBpmnModelEN(outBpmnEN.toString());
        System.out.println("BPMN model written to: " + outBpmnEN);
        builder.generateBpmnModelPT(outBpmnPT.toString());
        System.out.println("BPMN model written to: " + outBpmnPT);

        // optional forms
        if (builder.hasFormsToGenerate()) {
            Files.createDirectories(formOutputDir);
            builder.generateFormDefinitions(formOutputDir);
            System.out.println("Generated forms in: " + formOutputDir);
        } else {
            System.out.println("No forms for: " + baseName);
        }

        // merged ontology
        Path mergedOutput = mergedDir.resolve(baseName + ".ttl");
        ontologyService.saveMergedOntologyAsTurtle(mergedOutput);
        System.out.println("Merged ontology at: " + mergedOutput.toAbsolutePath());
    }
}

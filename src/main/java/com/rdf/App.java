package com.rdf;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rdf.generator.BpmnModelBuilder;
import com.rdf.report.MatrixReport;
import com.rdf.util.JsonFlattener;

import be.ugent.rml.cli.Main;

public class App {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Scans src/main/resources/jsons for JSON files, splits each into a Flow and Request variant,
     * then processes each _Flow.json through the existing RDF and BPMN pipeline.
     *
     * args:
     *  0: (optional) RML template file with placeholder {{JSON_SOURCE}}
     *  1: (optional) output RDF folder (instances will be written here)
     *  2: (optional) smartFlow TTL ontology path
     *  3: (optional) BPMN ontology TTL path
     *  4: (optional) mapping OWL TTL path
     */
    public static void main(String[] args) throws Exception {
        // where raw JSONs live:
        Path jsonDir    = Paths.get("src/main/resources/jsons");
        Path flowDir    = jsonDir.resolve("smartFlow");
        Path requestDir = jsonDir.resolve("smartForm");

        // all of pipeline outputs:
        Path instancesDir = Paths.get("src/main/resources/output/instances");
        Path bpmnDir      = Paths.get("src/main/resources/output/bpmn");
        Path mergedDir    = Paths.get("src/main/resources/output/merged");
        Path reportDir    = Paths.get("src/main/resources/output/report");

        // 1) create split JSON folders
        Files.createDirectories(flowDir);
        Files.createDirectories(requestDir);

        // 2) create all pipeline output folders up front
        Files.createDirectories(instancesDir);
        Files.createDirectories(bpmnDir);
        Files.createDirectories(mergedDir);
        Files.createDirectories(reportDir);

        // now go
        processJsonAndFlow(jsonDir, flowDir, requestDir, args);
    }

    private static void processJsonAndFlow(Path jsonDir, Path flowDir, Path requestDir, String[] args) throws Exception {
        // 1) Split each JSON into Flow and Request files
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jsonDir, "*.json")) {
            for (Path p : stream) {
                String fileName = p.getFileName().toString();
                JsonNode root = MAPPER.readTree(p.toFile());
                if (!root.has("flowTemplate")) {
                    System.out.println("Skipping file " + fileName + ": missing flowTemplate");
                    continue;
                }

                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));

                // Extract Flow
                JsonNode flowNode = root.get("flowTemplate");
                ObjectNode flowRoot = MAPPER.createObjectNode().set("flowTemplate", flowNode);
                Path outFlow = flowDir.resolve(baseName + "_Flow.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outFlow.toFile(), flowRoot);

                // Extract Request
                ObjectNode requestRoot = (ObjectNode) root.deepCopy();
                requestRoot.remove("flowTemplate");
                Path outRequest = requestDir.resolve(baseName + "_Request.json");
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(outRequest.toFile(), requestRoot);
            }
        }

        // 2) Process each generated Flow file through the RDF + BPMN pipeline
        try (DirectoryStream<Path> flowFiles = Files.newDirectoryStream(flowDir, "*_Flow.json")) {
            for (Path flowFile : flowFiles) {
                String flowFileName = flowFile.getFileName().toString();
                System.out.println("-> processing flow: " + flowFileName);
                try {
                    JsonNode root = MAPPER.readTree(flowFile.toFile());
                    JsonNode flowTemplate = root.get("flowTemplate");
                    if (flowTemplate == null || !flowTemplate.isObject()) {
                        System.out.println("   Skipped " + flowFileName + ": invalid or missing flowTemplate");
                        continue;
                    }
                    JsonNode config = flowTemplate.get("config");
                    JsonNode actionNodes = (config != null) ? config.get("actionNodes") : null;
                    if (actionNodes == null || !actionNodes.isArray() || actionNodes.size() == 0) {
                        System.out.println("   Skipped " + flowFileName + ": no actionNodes found or empty");
                        continue;
                    }
                    processFile(flowFile.toString(), args);
                } catch (Exception e) {
                    System.out.println("   Error processing file " + flowFileName + ": " + e.getMessage());
                    e.printStackTrace(System.out);
                }
                System.out.println();
            }
        }
    }

    private static void processFile(String inputJson, String[] args) throws Exception {
        String fileName = Paths.get(inputJson).getFileName().toString();
        String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;

        // default or overridden by args
        String rmlTemplate    = args.length > 0 ? args[0] : "src/main/resources/rml/flowTemplate.rml.ttl";
        String outputRdfDir   = args.length > 1 ? args[1] : "src/main/resources/output/instances/";
        String smartFlowTTL   = args.length > 2 ? args[2] : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl        = args.length > 3 ? args[3] : "src/main/resources/ontologies/bpmn/bpmn.ttl";
        String mappingOwl     = args.length > 4 ? args[4] : "src/main/resources/ontologies/mapping.ttl";

        // 1) build and write a temporary RML mapping
        Path template = Paths.get(rmlTemplate);
        String transfJson = JsonFlattener.convert(inputJson);
        String mappingTurtle = Files.readString(template).replace("{{JSON_SOURCE}}", transfJson);

        Path tempMapping = Paths.get("target/temp-mapping-" + baseName + ".ttl");
        Files.createDirectories(tempMapping.getParent());
        Files.writeString(tempMapping, mappingTurtle);
        System.out.println("RML mapping template written to: " + tempMapping.toAbsolutePath());

        // 2) run RMLMapper to produce individuals
        String outputRdf = outputRdfDir + baseName + "_individuals.ttl";
        Main.main(new String[] {"-m", tempMapping.toString(), "-o", outputRdf});
        System.out.println("RDF output written to: " + outputRdf);

        // 3) OWLAPI + Reasoning
        OntologyService ontologyService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );

        // 4) append to the report CSV
        MatrixReport report = new MatrixReport(ontologyService);
        report.gatherCounts();
        report.appendToCsv(baseName);
        System.out.println("Appended counts to matrix-report.csv under column: " + baseName);

        // 5) generate BPMN model
        String bpmnOutputPath = "src/main/resources/output/bpmn/" + baseName + ".bpmn";
        new BpmnModelBuilder(ontologyService).generateBpmnModel(bpmnOutputPath);
        System.out.println("BPMN model written to: " + bpmnOutputPath);

        // 6) save the merged ontology
        Path mergedOutput = Paths.get("src/main/resources/output/merged/" + baseName + ".ttl");
        ontologyService.saveMergedOntologyAsTurtle(mergedOutput);
        System.out.println("Merged ontology written to: " + mergedOutput.toAbsolutePath());
    }
}

package com.rdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.semanticweb.owlapi.model.OWLOntologyStorageException;

import com.rdf.generator.BpmnModelBuilder;

import be.ugent.rml.cli.Main;

public class App {
    /**
     * args:
     * 0: input JSON file (e.g., src/main/resources/jsons/MarriageLeaveFlow.json)
     * 1: RML template file with placeholder {{JSON_SOURCE}}
     * 2: output RDF file (e.g., target/smartFlow.ttl)
     * 3: BPMN ontology TTL (e.g., src/main/resources/ontologies/bpmn.ttl)
     * 4: mapping OWL TTL (e.g., src/main/resources/ontologies/transform.owl.ttl)
     */
    public static void main(String[] args) throws Exception {
        // 0) Unpack arguments
        String inputJson      = args.length > 0 ? args[0] : "src/main/resources/jsons/MarriageLeaveFlow_Flow-manual.json";
        String rmlTemplate    = args.length > 1 ? args[1] : "src/main/resources/ontologies/flowTemplate.rml.ttl";
        String outputRdf      = args.length > 2 ? args[2] : "src/main/resources/output/instances/individuals.ttl";
        String smartFlowTTL   = args.length > 3 ? args[3] : "src/main/resources/ontologies/smartFlow.ttl";
        String bpmnTtl        = args.length > 4 ? args[4] : "src/main/resources/ontologies/bpmn.ttl";
        String mappingOwl     = args.length > 5 ? args[5] : "src/main/resources/ontologies/mapping.ttl";

        // 1) Read RML template and replace placeholder with actual JSON path
        Path template = Paths.get(rmlTemplate);
        // String transfJson = JsonFlattener.convert(inputJson);
        String mappingTurtle = Files.readString(template);
        mappingTurtle = mappingTurtle.replace("{{JSON_SOURCE}}", inputJson); // transfJson);
        Path tempMapping = Paths.get("target/temp-mapping.ttl");
        Files.createDirectories(tempMapping.getParent());
        Files.writeString(tempMapping, mappingTurtle);

        // 2) Run RMLMapper on the generated mapping
        Main.main(new String[] {
            "-m", tempMapping.toString(),
            "-o", outputRdf,
            "-s", "turtle"
        });

        // 3) OWLAPI + Reasoning
        OntologyService ontologyService = new OntologyService(
            Paths.get(smartFlowTTL),
            Paths.get(outputRdf),
            Paths.get(bpmnTtl),
            Paths.get(mappingOwl)
        );

        // // ------------------------------------------------------------
        // //  4) Print all inferred sf:has_nextNode links
        // // ------------------------------------------------------------
        // ontologyService.printAllInferredNextNodeLinks();

        // // ------------------------------------------------------------
        // //  5) Print all created bbo:ExclusiveGateway instances
        // // ------------------------------------------------------------
        // ontologyService.printAllExclusiveGateways();

        // // ------------------------------------------------------------
        // //  6) Print all created bbo:SequenceFlow instances (with id & name)
        // // ------------------------------------------------------------
        // ontologyService.printAllSequenceFlows();

        // // ------------------------------------------------------------
        // //  7) Print all bbo:FlowNode individuals, prefixed by their direct class
        // // ------------------------------------------------------------
        // ontologyService.printAllFlowNodesWithClass();

        // // ------------------------------------------------------------
        // //  8) Print all bbo:SequenceFlow individuals, prefixed by their direct class
        // // ------------------------------------------------------------
        // ontologyService.printAllSequenceFlowsWithClass();

        // ontologyService.printAllLanesIndidividuals();

        BpmnModelBuilder builder = new BpmnModelBuilder(ontologyService);
        builder.generateBpmnModel();

        // ------------------------------------------------------------
        //  9) (Optional) Save merged ontology (with all new elements) to Turtle
        // ------------------------------------------------------------
        try {
            Path outputMerged = Paths.get("src/main/resources/output/merged/mergedOntology.ttl");
            ontologyService.saveMergedOntologyAsTurtle(outputMerged);
            System.out.println("\nMerged ontology (with all new elements) written to: " +
                               outputMerged.toAbsolutePath());
        } catch (IOException | OWLOntologyStorageException e) {
            e.printStackTrace();
            System.err.println("Failed to save merged ontology: " + e.getMessage());
        }
    }
}
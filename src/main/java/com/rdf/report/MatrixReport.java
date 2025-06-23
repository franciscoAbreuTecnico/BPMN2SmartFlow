package com.rdf.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.owlapi.model.OWLNamedIndividual;

import com.rdf.service.OntologyService;

/**
 * MatrixReport collects counts of SmartFlow and BPMN elements
 * and appends them to a single CSV in resources/output/report.
 * Each JSON run produces two columns: <name>_SmartFlow and <name>_BPMN.
 * On each instantiation, any existing CSV is deleted to start fresh.
 */
public class MatrixReport {
    private static final List<String> MAPPINGS = Arrays.asList(
        "Queue > Lane",
        "StartNode > ProcessStartEvent",
        "ActionNode > UserTask",
        // TODO Change it to ScriptTask when implemented
        "ActionProcessor > ServiceTask",
        "TerminalNode > EndEvent",
        "Field > DataObject",
        "Sequence > SequenceFlow",
        "Gate > ExclusiveGateway",
        "Action > NA",
        "Button > NA",
        "Form > NA"
    );

    private static final Path OUTPUT_DIR = Paths.get("src/main/resources/output/report");
    private static final Path CSV_PATH   = OUTPUT_DIR.resolve("matrix-report.csv");

    private final OntologyService service;
    private final Map<String, Integer> sfCounts = new LinkedHashMap<>();
    private final Map<String, Integer> bpmnCounts = new LinkedHashMap<>();

    public MatrixReport(OntologyService service) throws IOException {
        this.service = service;
        Files.createDirectories(OUTPUT_DIR);
        // ensure CSV exists; if not, will be created on first write
        if (!Files.exists(CSV_PATH)) {
            // initialize with header row
            List<String[]> init = new ArrayList<>();
            init.add(new String[] {"Mapping"});
            for (String key : MAPPINGS) {
                init.add(new String[] { key });
            }
            List<String> lines = new ArrayList<>();
            for (String[] cols : init) lines.add(String.join(",", cols));
            Files.write(CSV_PATH, lines, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    /**
     * Gather counts from the OntologyService.
     */
    public void gatherCounts() {
        // SmartFlow counts
        sfCounts.put("Queue > Lane", service.getInstances("Queue", false).size());
        sfCounts.put("StartNode > ProcessStartEvent", service.getInstances("StartNode", false).size());
        sfCounts.put("ActionNode > UserTask", service.getInstances("ActionNode", false).size());
        sfCounts.put("ActionProcessor > ServiceTask", service.getInstances("ActionProcessor", false).size());
        sfCounts.put("TerminalNode > EndEvent", service.getInstances("TerminalNode", false).size());
        sfCounts.put("Field > DataObject", service.getInstances("Field", false).size());

        // Special: Sequence/Gate
        int seq = 0, gate = 0;
        Set<OWLNamedIndividual> actionNodes = service.getInstances("Element", false);
        for (OWLNamedIndividual node : actionNodes) {
            Set<OWLNamedIndividual> nexts = service.getInferredNextNodes(node);
            if (nexts.size() == 1) {
                seq++;
            } else if (nexts.size() > 1) {
                gate++;
                seq += 1 + nexts.size();
            }
        }
        sfCounts.put("Sequence > SequenceFlow", seq);
        sfCounts.put("Gate > ExclusiveGateway", gate);

        // Additional SmartFlow-only elements
        sfCounts.put("Action > NA", service.getInstances("Action", false).size());
        sfCounts.put("Button > NA", service.getInstances("Button", false).size());
        sfCounts.put("Form > NA", service.getInstances("Form", false).size());

        // BPMN counts
        bpmnCounts.put("Queue > Lane", service.getAllLaneIndividuals().size());
        bpmnCounts.put("StartNode > ProcessStartEvent", service.getInstances("ProcessStartEvent", false).size());
        bpmnCounts.put("ActionNode > UserTask", service.getInstances("UserTask", false).size());
        bpmnCounts.put("ActionProcessor > ServiceTask", service.getInstances("ServiceTask", false).size());
        bpmnCounts.put("TerminalNode > EndEvent", service.getInstances("EndEvent", false).size());
        bpmnCounts.put("Field > DataObject", service.getInstances("DataResource", false).size());
        bpmnCounts.put("Sequence > SequenceFlow", service.getAllSequenceFlowIndividuals().size());
        bpmnCounts.put("Gate > ExclusiveGateway", service.getAllExclusiveGatewayIndividuals().size());
        bpmnCounts.put("Action > NA", null);
        bpmnCounts.put("Button > NA", null);
        bpmnCounts.put("Form > NA", null);
    }

    /**
     * Append counts to a single, shared CSV.
     * @param columnName  the name to use for this run’s column header (e.g. "Order1_Flow")
     */
    public void appendToCsv(String columnBase) throws IOException {
        List<String> lines = Files.readAllLines(CSV_PATH, StandardCharsets.UTF_8);
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) rows.add(line.split(",", -1));

        // Extend header
        String[] header = Arrays.copyOf(rows.get(0), rows.get(0).length + 2);
        header[header.length - 2] = columnBase;
        header[header.length - 1] = "";
        rows.set(0, header);

        // Extend data rows
        for (int i = 0; i < MAPPINGS.size(); i++) {
            String key = MAPPINGS.get(i);
            String[] row = Arrays.copyOf(rows.get(i + 1), rows.get(i + 1).length + 2);
            // SmartFlow count
            Integer sfVal = sfCounts.get(key);
            row[row.length - 2] = sfVal != null ? sfVal.toString() : "";
            // BPMN count (empty if null)
            Integer bpmnVal = bpmnCounts.get(key);
            row[row.length - 1] = bpmnVal != null ? bpmnVal.toString() : "";
            rows.set(i + 1, row);
        }

        // Write back
        List<String> out = new ArrayList<>();
        for (String[] cols : rows) out.add(String.join(",", cols));
        Files.write(CSV_PATH, out, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
}
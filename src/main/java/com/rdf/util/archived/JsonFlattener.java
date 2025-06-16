package com.rdf.util.archived;

import java.io.File;
import java.io.IOException;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonFlattener {

    private static final ObjectMapper mapper = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);
    
    /**
     * Reads a JSON file, transforms each actionNode's "actions" object into an array of actions
     * with added "name" and "parentId" fields, writes the result to a new JSON file,
     * and returns the path to the transformed file, preserving original break lines.
     */
    public static String convert(String inputPath) throws IOException {
        // Parse input JSON
        JsonNode root = mapper.readTree(new File(inputPath));
        ArrayNode actionNodes = (ArrayNode) root.path("flowTemplate").path("config").path("actionNodes");

        // Iterate over each actionNode and flatten actions
        for (JsonNode node : actionNodes) {
            ObjectNode actionNode = (ObjectNode) node;
            String parentId = actionNode.path("id").asText();
            JsonNode actionsNode = actionNode.get("actions");

            if (actionsNode != null && actionsNode.isObject()) {
                ObjectNode actionsObj = (ObjectNode) actionsNode;
                ArrayNode actionsArray = mapper.createArrayNode();

                actionsObj.fields().forEachRemaining(entry -> {
                    String name = entry.getKey();
                    ObjectNode details = (ObjectNode) entry.getValue();
                    ObjectNode newAction = mapper.createObjectNode();

                    newAction.put("name", name);
                    newAction.put("parentId", parentId);
                    details.fieldNames().forEachRemaining(field -> newAction.set(field, details.get(field)));
                    actionsArray.add(newAction);
                });

                actionNode.set("actions", actionsArray);
            }
        }

        // Prepare custom pretty printer to preserve multi-line arrays and objects
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);

        // Write transformed JSON with custom formatting
        String outputPath = inputPath.replace(".json", "-converted.json");
        mapper.writer(pp).writeValue(new File(outputPath), root);
        return outputPath;
    }
}
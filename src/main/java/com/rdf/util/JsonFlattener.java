package com.rdf.util;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;

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
     * Main entry: read JSON, transform each actionNode, write "-converted.json".
     */
    public static String convert(String inputPath) throws IOException {
        // mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        JsonNode root = mapper.readTree(new File(inputPath));

        JsonNode flowTemplate = root.path("flowTemplate").path("config");
        if (flowTemplate.has("forms")) {
            for (JsonNode formObj : flowTemplate.get("forms")) {
                JsonNode formDef = formObj.path("form");
                transformTitlesInForm(formDef);
            }
        }

        ArrayNode actionNodes = (ArrayNode) root
            .path("flowTemplate")
            .path("config")
            .path("actionNodes");

        for (JsonNode n : actionNodes) {
            ObjectNode node = (ObjectNode) n;

            // 1) actions → array
            if (node.has("actions")) {
                node.set("actions", flattenActions(node));
            }

            // 2) actionProcessor → array
            if (node.has("actionProcessor")) {
                node.set("actionProcessor", flattenProcessors(node));
            }

            // 3) rewrite any button handlers
            rewriteButtonHandlers(node);

            // 4) sanitize queue
            if (node.has("queue")) {
                node.put("queue", sanitizeQueue(node.get("queue").asText()));
            }
        }

        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter ind = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(ind);
        pp.indentArraysWith(ind);

        String outPath = inputPath.replace(".json", "-converted.json");
        mapper.writer(pp).writeValue(new File(outPath), root);
        return outPath;
    }

    /**
     * Strip braces {{…}}, remove accents, turn any run of non-alphanumeric into single hyphens.
     */
    private static String sanitizeQueue(String raw) {
        String s = raw.replaceAll("[\\{\\}]", "");
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                      .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("[^\\p{Alnum}]+", "-")
             .replaceAll("-+", "-")
             .replaceAll("^-|-$", "");
        return s;
    }

    /**
     * Converts the 'actions' object -> array, adding 'id','name','parentId'.
     */
    private static ArrayNode flattenActions(ObjectNode node) {
        ArrayNode out = mapper.createArrayNode();
        JsonNode actionsNode = node.get("actions");
        if (actionsNode != null && actionsNode.isObject()) {
            String parentId = node.path("id").asText();
            ObjectNode actionsObj = (ObjectNode) actionsNode;

            actionsObj.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                ObjectNode original = (ObjectNode) entry.getValue();
                ObjectNode action = mapper.createObjectNode();

                // copy all original props
                original.fields()
                        .forEachRemaining(f -> action.set(f.getKey(), f.getValue()));

                // new fields
                String id = name + "-" + parentId;
                action.put("id", id);
                action.put("name", name);

                out.add(action);
            });
        }
        return out;
    }

    /**
     * Converts the 'actionProcessor' object -> array, adding 'id','name',
     * and rewriting any 'applyOn' to point at the new action IDs.
     */
    private static ArrayNode flattenProcessors(ObjectNode node) {
        ArrayNode out = mapper.createArrayNode();
        JsonNode procNode = node.get("actionProcessor");
        if (procNode != null && procNode.isObject()) {
            String parentId = node.path("id").asText();
            ObjectNode procObj = (ObjectNode) procNode;

            procObj.fields().forEachRemaining(entry -> {
                String procName = entry.getKey();         // e.g. "onEnterNode" or "onSuccess"
                JsonNode val = entry.getValue();

                // handle a single object as array of one
                if (val.isObject()) {
                    val = mapper.createArrayNode().add(val);
                }

                if (val.isArray()) {
                    for (JsonNode item : (ArrayNode) val) {
                        ObjectNode p = mapper.createObjectNode();
                        ((ObjectNode) item).fields()
                            .forEachRemaining(f -> p.set(f.getKey(), f.getValue()));

                        // new fields
                        String transformedFlowProcessor = "";
                        if(p.has("flowProcessor")) {
                            String flowProcessor = p.get("flowProcessor").asText();
                            transformedFlowProcessor = flowProcessor.trim().replaceAll("\\s+", "_");
                        }
                        String id = procName + "-" + parentId + "-" + transformedFlowProcessor;
                        p.put("id", id);
                        p.put("name", procName);

                        // rewrite any applyOn
                        if (p.has("applyOn")) {
                            String applyOn = p.get("applyOn").asText();
                            p.put("applyOn", applyOn + "-" + parentId);
                        }

                        out.add(p);
                    }
                }
            });
        }
        return out;
    }

    private static void rewriteButtonHandlers(ObjectNode node) {
        if (!node.has("buttons") || !node.get("buttons").isArray()) return;
        String parentId = node.path("id").asText();

        for (JsonNode b : (ArrayNode) node.get("buttons")) {
            ObjectNode btn = (ObjectNode) b;

            // 1) rename the button’s own id
            if (btn.has("id")) {
                String btnId = btn.get("id").asText();
                btn.put("id", btnId + "-" + parentId);
            }

            // 2) rename the handler action
            if (btn.has("handlers") && btn.get("handlers").has("action")) {
                String act = btn.get("handlers").get("action").asText();
                btn.withObject("handlers")
                .put("action", act + "-" + parentId);
            }
        }
    }

    private static void transformTitlesInForm(JsonNode node) {
        if (node == null) return;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (java.util.Iterator<String> it = obj.fieldNames(); it.hasNext();) {
                String key = it.next();
                JsonNode value = obj.get(key);

                if ("title".equals(key) && value != null) {
                    if (value.isObject()) {
                        ObjectNode locObj = (ObjectNode) value;
                        for (java.util.Iterator<String> langIt = locObj.fieldNames(); langIt.hasNext();) {
                            String lang = langIt.next();
                            JsonNode langVal = locObj.get(lang);
                            if (langVal != null && langVal.isTextual()) {
                                String orig = langVal.asText();
                                String transformed = orig.replace(" ", "_");
                                locObj.put(lang, transformed);
                            }
                        }
                    } else if (value.isTextual()) {
                        obj.put(key, value.asText().replace(" ", "_"));
                    }
                } else {
                    transformTitlesInForm(value);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                transformTitlesInForm(child);
            }
        }
    }
}

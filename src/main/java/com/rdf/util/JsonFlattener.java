package com.rdf.util;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final List<String> changedActionNodeIds = new ArrayList<>();

    /**
     * Main entry: read JSON, transform each actionNode, write "-converted.json".
     */
    public static String convert(String inputPath) throws IOException {
        changedActionNodeIds.clear(); // Reset list per file!

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

        // 1. Collect ordered IDs of all actionNodes after ID transformation
        List<String> orderedIds = new ArrayList<>();
        int nodeIdx = 0;
        for (JsonNode n : actionNodes) {
            String oldId = n.get("id").asText();
            String newId = resolveId(oldId);
            orderedIds.add(newId);

            // Track if id changed
            if (!oldId.equals(newId)) {
                changedActionNodeIds.add(newId);
            }
            nodeIdx++;
        }

        // *** Compute unique queues and their first-appearance order ***
        Map<String, Integer> queueFirstOrder = new LinkedHashMap<>();
        int queueAppearanceIdx = 0;
        for (JsonNode n : actionNodes) {
            if (n.has("queue")) {
                String sanitizedQueue = sanitizeQueue(n.get("queue").asText());
                if (!queueFirstOrder.containsKey(sanitizedQueue)) {
                    queueFirstOrder.put(sanitizedQueue, queueAppearanceIdx);
                    queueAppearanceIdx++;
                }
            }
        }

        // 2. Update actionNodes with new IDs, flatten, and handle 'to' field rewriting
        int index = 0;
        for (JsonNode n : actionNodes) {
            ObjectNode node = (ObjectNode) n;

            // --- [START] Transform 'id' field as required ---
            if (node.has("id")) {
                String newId = orderedIds.get(index);
                node.put("id", newId);
            }
            // --- [END] Transform 'id' field as required ---

            // 1) actions → array (now with 'to' rewriting)
            if (node.has("actions")) {
                node.set("actions", flattenActionsAndRewriteTo(node, index, orderedIds));
            }

            // 2) actionProcessor → array
            if (node.has("actionProcessor")) {
                node.set("actionProcessor", flattenProcessors(node));
            }

            // 3) rewrite any button handlers
            rewriteButtonHandlers(node);

            // 4) sanitize queue and set **first-appearance queueOrder**
            if (node.has("queue")) {
                String rawQueue = node.get("queue").asText();
                String sanitizedQueue = sanitizeQueue(rawQueue);
                node.put("queue", sanitizedQueue);

                Integer qOrder = queueFirstOrder.get(sanitizedQueue);
                if (qOrder != null) {
                    node.put("queueOrder", qOrder);
                }
            }

            index++;
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
     * Transform 'id' according to the following rules:
     * - Replace any {{something-count}} with 0
     * - For any other {{var}}, replace with "var.1"
     * - For multiple variables, all are handled
     */
    private static String resolveId(String id) {
        Pattern p = Pattern.compile("\\{\\{([^\\}]+)\\}\\}");
        Matcher m = p.matcher(id);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String var = m.group(1);
            if (var.endsWith("count")) {
                m.appendReplacement(sb, "0");
            } else {
                // transform {{step-3}} → step-3.1
                m.appendReplacement(sb, var + ".1");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Converts the 'actions' object -> array, adding 'id','name','parentId',
     * and rewriting 'to' field if needed.
     */
    private static ArrayNode flattenActionsAndRewriteTo(ObjectNode node, int currentIndex, List<String> orderedIds) {
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
                original.fields().forEachRemaining(f -> action.set(f.getKey(), f.getValue()));

                // rewrite 'to' field if necessary
                if (action.has("to")) {
                    String toVal = action.get("to").asText();

                    // --- NEW: If {{go-step-X}}, replace with ["go-step-X-single", "go-step-X-multiple"]
                    Pattern goStepPattern = Pattern.compile("\\{\\{go-step-(\\d+)\\}\\}");
                    Matcher matcher = goStepPattern.matcher(toVal);
                    if (matcher.matches()) {
                        String step = matcher.group(1);
                        ArrayNode arr = mapper.createArrayNode();
                        arr.add("go-step-" + step + "-single");
                        arr.add("go-step-" + step + "-multiple");
                        action.set("to", arr);
                    }
                    // --- EXISTING: Handle next/next-step pattern
                    else if ("{{next}}".equals(toVal) || "{{next-step}}".equals(toVal)) {
                        String newTo;
                        if (currentIndex + 1 < orderedIds.size()) {
                            newTo = orderedIds.get(currentIndex + 1);
                        } else if (currentIndex > 0) {
                            newTo = orderedIds.get(currentIndex - 1);
                        } else {
                            newTo = toVal; // fallback (should never happen)
                        }
                        action.put("to", newTo);
                    }
                    // else leave as is
                }

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

    private static String sanitizeQueue(String raw) {
        String s = raw.replaceAll("[\\{\\}]", "");
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                      .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        s = s.replaceAll("[^\\p{Alnum}]+", "-")
             .replaceAll("-+", "-")
             .replaceAll("^-|-$", "");
        return s;
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

    public static List<String> getChangedActionNodeIds() {
        return new ArrayList<>(changedActionNodeIds);
    }

}

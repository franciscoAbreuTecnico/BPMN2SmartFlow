package com.rdf.extractor.enums;

public enum PatternType {
    FLOW_PROCESSORS,
    REQUEST_PROCESSORS,
    PAYLOAD_PROVIDERS;

    public static PatternType fromString(String s) {
        if (s.contains("FLOW_PROCESSORS")) return FLOW_PROCESSORS;
        if (s.contains("REQUEST_PROCESSORS")) return REQUEST_PROCESSORS;
        if (s.contains("PAYLOAD_PROVIDERS")) return PAYLOAD_PROVIDERS;
        return null;
    }
}

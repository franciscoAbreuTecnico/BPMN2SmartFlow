package com.rdf.exception;

public class BpmnGenerationException extends Exception {
    public BpmnGenerationException(String message) {
        super(message);
    }

    public BpmnGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}

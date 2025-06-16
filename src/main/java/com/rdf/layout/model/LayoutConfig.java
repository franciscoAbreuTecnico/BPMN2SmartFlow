package com.rdf.layout.model;

public class LayoutConfig {
  public double marginLeft     = 50;
  public double marginTop      = 50;
  public double horizontalGap  = 80;
  public double verticalGap    = 60;
  public double laneGap        = 0;
  public double annotationGap  = 30;

  /** how much extra space before column 0 (e.g. StartEvent) */
  public double column0Offset  = 30;
  /** horizontal space for lane label padding */
  public double laneLabelOffset = 30;

  public double defaultWidth(String t) {
    return switch (t.toLowerCase()) {
      case "startevent", "endevent" -> 36;
      case "task", "usertask", "servicetask", "scripttask" -> 100;
      case "exclusivegateway" -> 50;
      case "textannotation" -> 100;
      default -> 80;
    };
  }

  public double defaultHeight(String t) {
    return switch (t.toLowerCase()) {
      case "startevent", "endevent" -> 36;
      case "task", "usertask", "servicetask", "scripttask" -> 80;
      case "exclusivegateway" -> 50;
      case "textannotation" -> 30;
      default -> 60;
    };
  }
}
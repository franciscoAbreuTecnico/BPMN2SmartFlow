package com.rdf.layout.test;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;

import com.rdf.layout.engine.AutoLayoutEngine;
import com.rdf.layout.model.LayoutConfig;

import org.camunda.bpm.model.bpmn.instance.Process;

public class LayoutExampleMain {

    public static void main(String[] args) {
        BpmnModelInstance model = Bpmn.createExecutableProcess("Process_1")
            .startEvent("startEvent_1").name("Start")
            .userTask("task_1").name("Receive Order")
            .exclusiveGateway("gateway_1").name("Decision")
            .userTask("task_2").name("Approve Order")
            .userTask("task_3").name("Reject Order")
            .userTask("task_4").name("Follow Order")
            .userTask("task_5").name("Purchase Order")
            .endEvent("endEvent_1").name("End")
            .done();

        Process proc = model.getModelElementById("Process_1");
        LaneSet ls = model.newInstance(LaneSet.class);
        ls.setId("laneSet_1");
        proc.addChildElement(ls);

        Lane sales = model.newInstance(Lane.class);
        sales.setId("lane_sales");
        sales.setName("Sales");
        Lane finance = model.newInstance(Lane.class);
        finance.setId("lane_finance");
        finance.setName("Finance");
        Lane purchase = model.newInstance(Lane.class);
        purchase.setId("lane_purchase");
        purchase.setName("Purchase");
        ls.addChildElement(sales);
        ls.addChildElement(finance);
        ls.addChildElement(purchase);

        sales.getFlowNodeRefs().add(model.getModelElementById("startEvent_1"));
        sales.getFlowNodeRefs().add(model.getModelElementById("task_1"));
        sales.getFlowNodeRefs().add(model.getModelElementById("gateway_1"));
        sales.getFlowNodeRefs().add(model.getModelElementById("task_4"));
        sales.getFlowNodeRefs().add(model.getModelElementById("endEvent_1"));

        finance.getFlowNodeRefs().add(model.getModelElementById("task_2"));
        finance.getFlowNodeRefs().add(model.getModelElementById("task_3"));

        purchase.getFlowNodeRefs().add(model.getModelElementById("task_5"));

        // Rebuild sequence flows
        for (SequenceFlow f : new ArrayList<>(model.getModelElementsByType(SequenceFlow.class))) {
            f.getParentElement().removeChildElement(f);
        }
        createSequenceFlow(model, "startEvent_1", "task_1", "flow_1");
        createSequenceFlow(model, "task_1", "gateway_1", "flow_2");
        createSequenceFlow(model, "gateway_1", "task_2", "flow_3");
        createSequenceFlow(model, "gateway_1", "task_3", "flow_4");
        createSequenceFlow(model, "gateway_1", "task_4", "flow_5");
        createSequenceFlow(model, "task_2", "endEvent_1", "flow_6");
        createSequenceFlow(model, "task_3", "endEvent_1", "flow_7");
        createSequenceFlow(model, "task_4", "endEvent_1", "flow_8");
        createSequenceFlow(model, "task_5", "endEvent_1", "flow_9");
        createSequenceFlow(model, "startEvent_1", "task_5", "flow_10");

        LayoutConfig cfg = new LayoutConfig();
        new AutoLayoutEngine().layout(model, cfg);

        try (FileWriter w = new FileWriter("src/main/resources/output/bpmn/testDiagramLayout.bpmn")) {
            w.write(Bpmn.convertToString(model));
            System.out.println("diagram.bpmn written.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void createSequenceFlow(BpmnModelInstance mi, String srcId, String tgtId, String flowId) {
        FlowNode src = mi.getModelElementById(srcId);
        FlowNode tgt = mi.getModelElementById(tgtId);
        SequenceFlow f = mi.newInstance(SequenceFlow.class);
        f.setId(flowId);
        f.setSource(src);
        f.setTarget(tgt);
        process(mi).addChildElement(f);
        src.getOutgoing().add(f);
        tgt.getIncoming().add(f);
    }

    private static Process process(BpmnModelInstance mi) {
        return mi.getModelElementById("Process_1");
    }
}

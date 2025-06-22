package com.rdf.layout.test;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Association;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.MessageFlow;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.TextAnnotation;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.camunda.bpm.model.bpmn.instance.dc.Bounds;
import org.camunda.bpm.model.bpmn.instance.di.Waypoint;

import org.camunda.bpm.model.bpmn.instance.Process;

public class LayoutExampleMain {

    public static class LayoutConfig {
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

    public static class LayoutData {
        public int gridRow, gridCol;
        public double x, y, width, height;
        public LayoutData(int r, int c) { gridRow = r; gridCol = c; }
    }

    public static class AutoLayoutEngine {
        private Map<FlowNode,LayoutData> globalLayout = new HashMap<>();
        private Map<String,FlowNode> occupied         = new HashMap<>();
        private double[] colX;
        private int globalMaxCol;

        private String cellKey(int r,int c)         { return r+"_"+c; }
        private boolean isOccupied(int r,int c)     { return occupied.containsKey(cellKey(r,c)); }
        private void occupy(int r,int c,FlowNode n) { occupied.put(cellKey(r,c),n); }

        /** 
         * Modified Kahn’s to break same‑lane cycles by flipping back‑edges.
         */
        public List<FlowNode> getTopologicalOrder(Collection<FlowNode> nodes) {
            Map<FlowNode,List<FlowNode>> succ = new HashMap<>();
            Map<FlowNode,Integer> inCount = new HashMap<>();
            for (FlowNode n : nodes) {
            succ.put(n, new ArrayList<>());
            inCount.put(n, 0);
            }
            for (FlowNode n : nodes) {
            for (SequenceFlow sf : n.getOutgoing()) {
                FlowNode t = sf.getTarget();
                if (sameLane(n,t)) {
                succ.get(n).add(t);
                inCount.put(t, inCount.get(t)+1);
                }
            }
            }
            Map<FlowNode,Integer> initial = new HashMap<>(inCount);
            Deque<FlowNode> S = new ArrayDeque<>();
            List<FlowNode> order = new ArrayList<>();
            Set<FlowNode> rem = new HashSet<>(nodes);
            inCount.forEach((n,c)->{ if(c==0) S.add(n); });

            while(!rem.isEmpty()) {
            if(!S.isEmpty()) {
                FlowNode n = S.pop();
                order.add(n);
                rem.remove(n);
                for(FlowNode m: succ.get(n)) {
                if(!rem.contains(m)) continue;
                int c = inCount.get(m)-1;
                inCount.put(m,c);
                if(c==0) S.push(m);
                }
            } else {
                FlowNode breakNode = rem.stream()
                .filter(n-> inCount.get(n) < initial.get(n))
                .findFirst().orElse(rem.iterator().next());
                List<FlowNode> preds = new ArrayList<>();
                for(FlowNode p: rem) {
                if(succ.get(p).contains(breakNode)) preds.add(p);
                }
                for(FlowNode p: preds) {
                succ.get(p).remove(breakNode);
                inCount.put(breakNode,inCount.get(breakNode)-1);
                succ.get(breakNode).add(p);
                inCount.put(p,inCount.get(p)+1);
                }
                if(inCount.get(breakNode)==0) S.push(breakNode);
            }
            }
            return order;
        }

        private boolean sameLane(FlowNode a, FlowNode b) {
            Lane la = getLane(a), lb = getLane(b);
            return la==null? lb==null : la.equals(lb);
        }
        private Lane getLane(FlowNode n) {
            for(Process p: n.getModelInstance().getModelElementsByType(Process.class))
            for(LaneSet ls: p.getChildElementsByType(LaneSet.class))
                for(Lane l: ls.getLanes())
                if(l.getFlowNodeRefs().contains(n))
                    return l;
            return null;
        }

        /**
         * Packs one lane into a grid, then compacts empty rows.
         */
        public Map<FlowNode,LayoutData> layoutLane(Collection<FlowNode> laneNodes,
                                                    LayoutConfig cfg) {
            Map<FlowNode,LayoutData> map = new HashMap<>();
            occupied.clear();
            List<FlowNode> sorted = getTopologicalOrder(laneNodes);
            int nextRow = 0;

            for(FlowNode n: sorted) {
            List<SequenceFlow> same  = new ArrayList<>();
            List<SequenceFlow> cross = new ArrayList<>();
            for(SequenceFlow sf: n.getIncoming()) {
                if(sameLane(sf.getSource(),n)) same.add(sf);
                else                            cross.add(sf);
            }
            if(!cross.isEmpty()) {
                LayoutData pd = globalLayout.get(cross.get(0).getSource());
                if(pd!=null) {
                int r=pd.gridRow, c=pd.gridCol+1;
                while(isOccupied(r,c)) r++;
                map.put(n,new LayoutData(r,c));
                occupy(r,c,n);
                continue;
                }
            }
            if(same.isEmpty()) {
                while(isOccupied(nextRow,0)) nextRow++;
                map.put(n,new LayoutData(nextRow,0));
                occupy(nextRow,0,n);
                nextRow++;
            } else {
                int maxCol=-1; double sum=0; int cnt=0;
                for(SequenceFlow sf: same) {
                LayoutData pd = map.get(sf.getSource());
                if(pd!=null) {
                    maxCol = Math.max(maxCol,pd.gridCol);
                    sum+=pd.gridRow; cnt++;
                }
                }
                int c=maxCol+1;
                int r=cnt>0? (int)Math.round(sum/cnt) : 0;
                while(isOccupied(r,c)) r++;
                map.put(n,new LayoutData(r,c));
                occupy(r,c,n);
            }
            }

            boolean merged;
            do {
            merged=false;
            int maxRow=map.values().stream().mapToInt(ld->ld.gridRow).max().orElse(-1);
            for(int r=0;r<maxRow;r++){
                Set<Integer> a=new HashSet<>(), b=new HashSet<>();
                for(LayoutData ld:map.values()){
                if(ld.gridRow==r)   a.add(ld.gridCol);
                if(ld.gridRow==r+1) b.add(ld.gridCol);
                }
                if(Collections.disjoint(a,b)){
                for(LayoutData ld:map.values()){
                    if(ld.gridRow==r+1)      ld.gridRow=r;
                    else if(ld.gridRow>r+1)  ld.gridRow--;
                }
                merged=true;
                break;
                }
            }
            } while(merged);

            return map;
        }

        /**
         * Orchestrates full layout then injects DI + enhanced routing.
         */
        public void layout(BpmnModelInstance mi, LayoutConfig cfg) {
            Process proc = mi.getModelElementsByType(Process.class)
                    .stream()
                    .findFirst()
                    .orElse(null);
            LaneSet ls = proc.getChildElementsByType(LaneSet.class)
                            .stream().findFirst().orElse(null);
            if(ls==null){ System.err.println("No lanes"); return; }

            // 1) grid each lane
            List<Lane> lanes = new ArrayList<>(ls.getLanes());
            Map<Lane,Map<FlowNode,LayoutData>> byLane = new LinkedHashMap<>();
            Map<Lane,Integer> maxC=new HashMap<>(), maxR=new HashMap<>();
            for(Lane lane:lanes){
            var m=layoutLane(lane.getFlowNodeRefs(),cfg);
            byLane.put(lane,m);
            m.forEach(globalLayout::put);
            maxC.put(lane,m.values().stream().mapToInt(ld->ld.gridCol).max().orElse(0));
            maxR.put(lane,m.values().stream().mapToInt(ld->ld.gridRow).max().orElse(0));
            }

            // 2) compute columns
            globalMaxCol = maxC.values().stream().max(Integer::compareTo).orElse(0);
            double[] colW = new double[globalMaxCol+1];
            byLane.values().forEach(m->m.forEach((n,ld)->{
            ld.width = cfg.defaultWidth(n.getElementType().getTypeName());
            colW[ld.gridCol] = Math.max(colW[ld.gridCol],ld.width);
            }));
            colX = new double[colW.length];
            colX[0] = cfg.marginLeft + cfg.column0Offset;
            for (int c = 1; c < colX.length; c++) {
            colX[c] = colX[c - 1] + colW[c - 1] + cfg.horizontalGap;
            }

            // 3) absolute x,y & bounds
            double curY=cfg.marginTop;
            Map<Lane,Double[]> laneBounds=new LinkedHashMap<>();
            for(Lane lane:lanes){
            var m=byLane.get(lane);
            if(m.isEmpty()) continue;
            int maxRow=maxR.get(lane);
            double[] rowH=new double[maxRow+1];
            m.forEach((n,ld)->{
                ld.height=cfg.defaultHeight(n.getElementType().getTypeName());
                rowH[ld.gridRow]=Math.max(rowH[ld.gridRow],ld.height);
            });
            double[] rowY=new double[maxRow+1];
            rowY[0]=curY;
            for(int r=1;r<rowY.length;r++){
                rowY[r]=rowY[r-1]+rowH[r-1]+cfg.verticalGap;
            }
            m.values().forEach(ld->{
                ld.x=colX[ld.gridCol]+(colW[ld.gridCol]-ld.width)/2;
                ld.x += cfg.laneLabelOffset;
                ld.y=rowY[ld.gridRow]+(rowH[ld.gridRow]-ld.height)/2;
            });
            double w = (colX[globalMaxCol] + colW[globalMaxCol] - cfg.marginLeft) + cfg.column0Offset + cfg.laneLabelOffset;
            double h=rowY[maxRow]+rowH[maxRow]-curY;
            laneBounds.put(lane, new Double[]{ cfg.marginLeft, curY, w, h });
            curY+=h+cfg.laneGap;
            }

            // 4) inject DI + enhanced edge routing
            addBPMNDI(mi,globalLayout,laneBounds,cfg);
        }

        private void addBPMNDI(
            BpmnModelInstance mi,
            Map<FlowNode,LayoutData> lm,
            Map<Lane,Double[]> bounds,
            LayoutConfig cfg
        ){
            Definitions defs = mi.getDefinitions();
            defs.getChildElementsByType(BpmnDiagram.class)
                .forEach(defs::removeChildElement);

            BpmnDiagram diagram = mi.newInstance(BpmnDiagram.class);
            diagram.setId("BPMNDiagram_1");
            BpmnPlane plane = mi.newInstance(BpmnPlane.class);
            plane.setId("BPMNPlane_1");
            Collaboration collaboration = mi.getModelElementsByType(Collaboration.class)
            .stream().findFirst().orElse(null);
            if (collaboration != null) {
            plane.setBpmnElement(collaboration);
            }
            diagram.setBpmnPlane(plane);

            // 1) Node shapes
            lm.forEach((n,ld)->{
            if(n instanceof TextAnnotation) return;
            BpmnShape s=mi.newInstance(BpmnShape.class);
            s.setId("Shape_"+n.getId());
            s.setBpmnElement(n);
            Bounds b=mi.newInstance(Bounds.class);
            b.setX(ld.x);b.setY(ld.y);
            b.setWidth(ld.width);b.setHeight(ld.height);
            s.setBounds(b);
            String typeName = n.getElementType().getTypeName().toLowerCase();
            if (typeName.contains("event") || typeName.contains("gateway")) {
                var label = mi.newInstance(org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnLabel.class);
                var labelBounds = mi.newInstance(Bounds.class);
                labelBounds.setX(ld.x);
                labelBounds.setY(ld.y + ld.height);
                labelBounds.setWidth(100);
                labelBounds.setHeight(20);
                label.setBounds(labelBounds);
                s.setBpmnLabel(label);
            }

            plane.addChildElement(s);
            });

            // 2) TextAnnotations
            for(TextAnnotation ta:mi.getModelElementsByType(TextAnnotation.class)){
            FlowNode target=null;
            for(Association as:mi.getModelElementsByType(Association.class)){
                if(as.getSource().equals(ta)&&as.getTarget() instanceof FlowNode){
                target=(FlowNode)as.getTarget(); break;
                }
                if(as.getTarget().equals(ta)&&as.getSource() instanceof FlowNode){
                target=(FlowNode)as.getSource(); break;
                }
            }
            if(target==null) continue;
            LayoutData tld=lm.get(target);
            if(tld==null) continue;
            double w=cfg.defaultWidth("textannotation");
            double h=cfg.defaultHeight("textannotation");
            double x=tld.x+(tld.width-w)/2;
            double y=tld.y-cfg.annotationGap-h;
            BpmnShape sa=mi.newInstance(BpmnShape.class);
            sa.setId("Shape_"+ta.getId());
            sa.setBpmnElement(ta);
            Bounds bb=mi.newInstance(Bounds.class);
            bb.setX(x);bb.setY(y);
            bb.setWidth(w);bb.setHeight(h);
            sa.setBounds(bb);
            plane.addChildElement(sa);
            }

            // 3) Lanes
            bounds.forEach((ln,bb)->{
            BpmnShape s=mi.newInstance(BpmnShape.class);
            s.setId("Shape_"+ln.getId());
            s.setBpmnElement(ln);
            Bounds b=mi.newInstance(Bounds.class);
            b.setX(bb[0] + cfg.laneLabelOffset);b.setY(bb[1]);
            b.setWidth(bb[2]);b.setHeight(bb[3]);
            s.setBounds(b);
            plane.addChildElement(s);
            });

            // 3b) Participant (Pool)
            for (Participant participant : mi.getModelElementsByType(Participant.class)) {
            BpmnShape shape = mi.newInstance(BpmnShape.class);
            shape.setId("Shape_" + participant.getId());
            shape.setBpmnElement(participant);

            // The bounds of the pool should wrap all lanes inside it
            // Calculate based on lane bounds
            Double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
            for (Double[] bb : bounds.values()) {
                minX = Math.min(minX, bb[0]);
                minY = Math.min(minY, bb[1]);
                maxX = Math.max(maxX, bb[0] + bb[2]);
                maxY = Math.max(maxY, bb[1] + bb[3]);
            }

            double poolX = minX;
            double poolY = minY;
            double poolWidth = (maxX - minX) + cfg.laneLabelOffset;
            double poolHeight = maxY - minY;

            Bounds b = mi.newInstance(Bounds.class);
            b.setX(poolX);
            b.setY(poolY);
            b.setWidth(poolWidth);
            b.setHeight(poolHeight);
            shape.setBounds(b);

            plane.addChildElement(shape);
            }

            // 4) SequenceFlow edges
            for(SequenceFlow flow:mi.getModelElementsByType(SequenceFlow.class)){
            FlowNode sNode=flow.getSource(), tNode=flow.getTarget();
            LayoutData sd=lm.get(sNode), td=lm.get(tNode);
            if(sd==null||td==null) continue;

            int tgtCol = td.gridCol;
            Lane srcLane=getLane(sNode), tgtLane=getLane(tNode);
            boolean sameLaneFlow = srcLane!=null && srcLane.equals(tgtLane);

            BpmnEdge edge=mi.newInstance(BpmnEdge.class);
            edge.setId("Edge_"+flow.getId());
            edge.setBpmnElement(flow);

            if(tgtCol==globalMaxCol){
                // A) horizontal-first to rightmost column
                double sx=sd.x+sd.width, sy=sd.y+sd.height/2;
                double tx=td.x, ty=td.y+td.height/2;
                edge.addChildElement(createWaypoint(mi,sx,sy));
                edge.addChildElement(createWaypoint(mi,tx,sy));
                edge.addChildElement(createWaypoint(mi,tx,ty));
            }
            else if(sameLaneFlow){
                // B) horizontal-first within lane
                double sx=sd.x+sd.width, sy=sd.y+sd.height/2;
                double tx=td.x, ty=td.y+td.height/2;
                edge.addChildElement(createWaypoint(mi,sx,sy));
                edge.addChildElement(createWaypoint(mi,tx,sy));
                if(Math.abs(ty-sy)>1e-6)
                edge.addChildElement(createWaypoint(mi,tx,ty));
            }
            else {
                // C) vertical-first across lanes
                boolean down = td.y>sd.y;
                double sx=sd.x+sd.width/2, sy=down?(sd.y+sd.height):sd.y;
                double tx=td.x, ty=td.y+td.height/2;
                edge.addChildElement(createWaypoint(mi,sx,sy));
                edge.addChildElement(createWaypoint(mi,sx,ty));
                edge.addChildElement(createWaypoint(mi,tx,ty));
            }

            plane.addChildElement(edge);
            }

            // 5) MessageFlow edges
            for (MessageFlow messageFlow : mi.getModelElementsByType(MessageFlow.class)) {
            FlowNode source = (FlowNode) messageFlow.getSource();
            FlowNode target = (FlowNode) messageFlow.getTarget();
            LayoutData src = lm.get(source);
            LayoutData tgt = lm.get(target);
            if (src == null || tgt == null) continue;

            BpmnEdge edge = mi.newInstance(BpmnEdge.class);
            edge.setId("Edge_" + messageFlow.getId());
            edge.setBpmnElement(messageFlow);

            double sx = src.x + src.width / 2;
            double sy = src.y + src.height / 2;
            double tx = tgt.x + tgt.width / 2;
            double ty = tgt.y + tgt.height / 2;

            edge.addChildElement(createWaypoint(mi, sx, sy));
            edge.addChildElement(createWaypoint(mi, tx, ty));

            plane.addChildElement(edge);
        }

            defs.addChildElement(diagram);
        }

        private static Waypoint createWaypoint(BpmnModelInstance mi,double x,double y){
            Waypoint w=mi.newInstance(Waypoint.class);
            w.setX(x);w.setY(y);
            return w;
        }
    }

    public static class BPMNDIInjector {
    }

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

        try (FileWriter w = new FileWriter("src/main/resources/output/bpmn/testDiagramLayout_All.bpmn")) {
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

//V6
package com.rdf.layout.test;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.camunda.bpm.model.bpmn.instance.dc.Bounds;
import org.camunda.bpm.model.bpmn.instance.di.Waypoint;

public class LayoutExampleMain {

    public static class LayoutConfig {
        public double marginLeft      = 60;
        public double marginTop       = 60;
        public double horizontalGap   = 100;
        public double verticalGap     = 100;
        public double laneGap         = 0;
        public double column0Offset   = 30;
        public double laneLabelOffset = 30;
        public double poolLabelOffset = 30;

        public double defaultWidth(String t) {
            switch (t.toLowerCase()) {
                case "startevent": case "endevent":     return 35;
                case "task": case "usertask":           return 80;
                case "servicetask": case "scripttask":  return 80;
                case "exclusivegateway":                return 40;
                default:                                return 80;
            }
        }
        public double defaultHeight(String t) {
            switch (t.toLowerCase()) {
                case "startevent": case "endevent":     return 35;
                case "task": case "usertask":           return 60;
                case "servicetask": case "scripttask":  return 60;
                case "exclusivegateway":                return 40;
                default:                                return 60;
            }
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

        private String cellKey(int r,int c) { return r+"_"+c; }
        private boolean isOccupied(int r,int c) { return occupied.containsKey(cellKey(r,c)); }
        private void occupy(int r,int c,FlowNode n) { occupied.put(cellKey(r,c),n); }

        public List<FlowNode> getTopologicalOrder(Collection<FlowNode> nodes) {
            Map<FlowNode,List<FlowNode>> succ = new HashMap<>();
            Map<FlowNode,Integer> inCount    = new HashMap<>();
            for(FlowNode n: nodes){ succ.put(n,new ArrayList<>()); inCount.put(n,0); }
            for(FlowNode n: nodes){
                for(SequenceFlow sf: n.getOutgoing()){
                    FlowNode t = sf.getTarget();
                    if(sameLane(n,t)){
                        succ.get(n).add(t);
                        inCount.put(t,inCount.get(t)+1);
                    }
                }
            }
            Map<FlowNode,Integer> initial = new HashMap<>(inCount);
            Deque<FlowNode> S = new ArrayDeque<>();
            inCount.forEach((n,c)->{ if(c==0) S.add(n); });
            Set<FlowNode> rem = new HashSet<>(nodes);
            List<FlowNode> order = new ArrayList<>();

            while(!rem.isEmpty()){
                if(!S.isEmpty()){
                    FlowNode n = S.pop();
                    order.add(n);
                    rem.remove(n);
                    for(FlowNode m: succ.get(n)){
                        if(!rem.contains(m)) continue;
                        int c = inCount.get(m)-1;
                        inCount.put(m,c);
                        if(c==0) S.push(m);
                    }
                } else {
                    FlowNode breakNode = rem.stream()
                            .filter(n->inCount.get(n)<initial.get(n))
                            .findFirst().orElse(rem.iterator().next());
                    List<FlowNode> preds = new ArrayList<>();
                    for(FlowNode p: rem) if(succ.get(p).contains(breakNode)) preds.add(p);
                    for(FlowNode p: preds){
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

        private boolean sameLane(FlowNode a, FlowNode b){
            Lane la = getLane(a), lb = getLane(b);
            return la==null? lb==null : la.equals(lb);
        }
        private Lane getLane(FlowNode n){
            for(Process p: n.getModelInstance().getModelElementsByType(Process.class))
                for(LaneSet ls: p.getChildElementsByType(LaneSet.class))
                    for(Lane l: ls.getLanes())
                        if(l.getFlowNodeRefs().contains(n)) return l;
            return null;
        }

        public Map<FlowNode,LayoutData> layoutLane(Collection<FlowNode> laneNodes, LayoutConfig cfg){
            occupied.clear();
            Map<FlowNode,LayoutData> map = new HashMap<>();
            List<FlowNode> sorted = getTopologicalOrder(laneNodes);
            int nextRow = 0;

            for(FlowNode n: sorted){
                List<SequenceFlow> same  = new ArrayList<>();
                List<SequenceFlow> cross = new ArrayList<>();
                for(SequenceFlow sf: n.getIncoming()){
                    if(sameLane(sf.getSource(),n)) same.add(sf);
                    else                            cross.add(sf);
                }
                if(!cross.isEmpty()){
                    LayoutData pd = globalLayout.get(cross.get(0).getSource());
                    if(pd!=null){
                        int r=pd.gridRow, c=pd.gridCol+1;
                        while(isOccupied(r,c)) r++;
                        map.put(n,new LayoutData(r,c));
                        occupy(r,c,n);
                        continue;
                    }
                }
                if(same.isEmpty()){
                    while(isOccupied(nextRow,0)) nextRow++;
                    map.put(n,new LayoutData(nextRow,0));
                    occupy(nextRow,0,n);
                    nextRow++;
                } else {
                    int maxCol=-1; double sum=0; int cnt=0;
                    for(SequenceFlow sf: same){
                        LayoutData pd = map.get(sf.getSource());
                        if(pd!=null){ maxCol=Math.max(maxCol,pd.gridCol); sum+=pd.gridRow; cnt++; }
                    }
                    int c=maxCol+1;
                    int r= cnt>0? (int)Math.round(sum/cnt) : 0;
                    while(isOccupied(r,c)) r++;
                    map.put(n,new LayoutData(r,c));
                    occupy(r,c,n);
                }
            }

            // merge empty rows
            boolean merged;
            do {
                merged=false;
                int maxRow = map.values().stream().mapToInt(ld->ld.gridRow).max().orElse(-1);
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
                        merged=true; break;
                    }
                }
            } while(merged);

            return map;
        }

        public void layout(BpmnModelInstance mi, LayoutConfig cfg, Map<Lane, Integer> laneOrderMap) {
            Process proc = mi.getModelElementsByType(Process.class)
                    .stream()
                    .findFirst()
                    .orElse(null);
            if(proc==null) throw new IllegalStateException("Process_1 not found");
            LaneSet ls = proc.getChildElementsByType(LaneSet.class).stream().findFirst()
                    .orElseThrow(()->new IllegalStateException("No lanes"));
            List<Lane> lanes = ls.getLanes().stream()
                .sorted(Comparator.comparingInt(lane -> laneOrderMap.getOrDefault(lane, Integer.MAX_VALUE)))
                .collect(Collectors.toList());

            Map<Lane,Map<FlowNode,LayoutData>> byLane = new LinkedHashMap<>();
            Map<Lane,Integer> maxC=new HashMap<>(), maxR=new HashMap<>();
            for(Lane lane:lanes){
                Map<FlowNode,LayoutData> m = layoutLane(lane.getFlowNodeRefs(),cfg);
                byLane.put(lane,m);
                m.forEach(globalLayout::put);
                maxC.put(lane,m.values().stream().mapToInt(ld->ld.gridCol).max().orElse(0));
                maxR.put(lane,m.values().stream().mapToInt(ld->ld.gridRow).max().orElse(0));
            }

            globalMaxCol = maxC.values().stream().max(Integer::compareTo).orElse(0);
            double[] colW = new double[globalMaxCol+1];
            byLane.values().forEach(m->m.forEach((n,ld)->{
                ld.width = cfg.defaultWidth(n.getElementType().getTypeName());
                colW[ld.gridCol] = Math.max(colW[ld.gridCol],ld.width);
            }));
            colX = new double[colW.length];
            colX[0]=cfg.marginLeft+cfg.column0Offset;
            for(int c=1;c<colX.length;c++){
                colX[c] = colX[c-1] + colW[c-1] + cfg.horizontalGap;
            }

            double curY = cfg.marginTop;
            Map<Lane,Double[]> laneBounds = new LinkedHashMap<>();
            for(Lane lane:lanes){
                Map<FlowNode,LayoutData> m = byLane.get(lane);
                if(m.isEmpty()) continue;
                int maxRow = maxR.get(lane);
                double[] rowH = new double[maxRow+1];
                m.forEach((n,ld)->{
                    ld.height = cfg.defaultHeight(n.getElementType().getTypeName());
                    rowH[ld.gridRow] = Math.max(rowH[ld.gridRow],ld.height);
                });
                double[] rowY = new double[maxRow+1];
                rowY[0]=curY+cfg.laneLabelOffset;
                for(int r=1;r<rowY.length;r++){
                    rowY[r] = rowY[r-1] + rowH[r-1] + cfg.verticalGap;
                }
                m.values().forEach(ld->{
                    ld.x = colX[ld.gridCol] + (colW[ld.gridCol]-ld.width)/2 + cfg.poolLabelOffset;
                    ld.y = rowY[ld.gridRow] + (rowH[ld.gridRow]-ld.height)/2;
                });
                double w = (colX[globalMaxCol]+colW[globalMaxCol]-cfg.marginLeft) + cfg.column0Offset;
                double h = rowY[maxRow] + rowH[maxRow] - curY + cfg.laneLabelOffset;
                laneBounds.put(lane,new Double[]{cfg.marginLeft,curY,w,h});
                curY += h + cfg.laneGap;
            }

            addBPMNDI(mi,globalLayout,laneBounds,cfg);
        }

        private void addBPMNDI(BpmnModelInstance mi,
                               Map<FlowNode,LayoutData> lm,
                               Map<Lane,Double[]> bounds,
                               LayoutConfig cfg) {
            Definitions defs = mi.getDefinitions();
            defs.getChildElementsByType(BpmnDiagram.class).forEach(defs::removeChildElement);

            BpmnDiagram diagram = mi.newInstance(BpmnDiagram.class);
            diagram.setId("BPMNDiagram_1");
            BpmnPlane plane = mi.newInstance(BpmnPlane.class);
            plane.setId("BPMNPlane_1");

            Collaboration coll = mi.getModelElementsByType(Collaboration.class).stream().findFirst().orElse(null);
            if(coll!=null) plane.setBpmnElement(coll);
            else           plane.setBpmnElement(mi.getModelElementById("Process_1"));
            diagram.setBpmnPlane(plane);

            lm.forEach((n,ld)->{
                BpmnShape s = mi.newInstance(BpmnShape.class);
                s.setId("Shape_" + n.getId());
                s.setBpmnElement(n);
                Bounds b = mi.newInstance(Bounds.class);
                b.setX(ld.x); b.setY(ld.y);
                b.setWidth(ld.width); b.setHeight(ld.height);
                s.setBounds(b);
                plane.addChildElement(s);
            });

            bounds.forEach((ln,bb)->{
                BpmnShape s = mi.newInstance(BpmnShape.class);
                s.setId("Shape_" + ln.getId());
                s.setBpmnElement(ln);
                Bounds b = mi.newInstance(Bounds.class);
                b.setX(bb[0] + cfg.poolLabelOffset);
                b.setY(bb[1]);
                b.setWidth(bb[2]);
                b.setHeight(bb[3]);
                s.setBounds(b);
                plane.addChildElement(s);
            });

            for(Participant p: mi.getModelElementsByType(Participant.class)){
                double minX=Double.MAX_VALUE, minY=Double.MAX_VALUE;
                double maxX=Double.MIN_VALUE, maxY=Double.MIN_VALUE;
                for(Double[] bb:bounds.values()){
                    minX=Math.min(minX,bb[0]);
                    minY=Math.min(minY,bb[1]);
                    maxX=Math.max(maxX,bb[0]+bb[2]);
                    maxY=Math.max(maxY,bb[1]+bb[3]);
                }
                double px=minX, py=minY;
                double pw=(maxX-minX)+cfg.poolLabelOffset;
                double ph=(maxY-minY);

                BpmnShape s = mi.newInstance(BpmnShape.class);
                s.setId("Shape_"+p.getId());
                s.setBpmnElement(p);
                Bounds b = mi.newInstance(Bounds.class);
                b.setX(px); b.setY(py);
                b.setWidth(pw); b.setHeight(ph);
                s.setBounds(b);
                plane.addChildElement(s);
            }

            // SequenceFlow edge routing (generic vertical/diagonal downward case)
            double diagramRightX = lm.values().stream().mapToDouble(ld->ld.x+ld.width).max().orElse(0);
            double corridorX = diagramRightX + cfg.horizontalGap*0.35;
            for(SequenceFlow flow: mi.getModelElementsByType(SequenceFlow.class)){
                FlowNode sNode = flow.getSource(), tNode = flow.getTarget();
                LayoutData sd=lm.get(sNode), td=lm.get(tNode);
                if(sd==null||td==null) continue;

                int tgtCol = td.gridCol;
                Lane sL=getLane(sNode), tL=getLane(tNode);
                boolean sameLaneFlow = sL!=null && sL.equals(tL);

                BpmnEdge edge = mi.newInstance(BpmnEdge.class);
                edge.setId("Edge_"+flow.getId());
                edge.setBpmnElement(flow);

                // GENERIC vertical/diagonal downward case:
                // If target is below source and at a column >= source (to right or directly below)
                boolean isDownward = (td.y > sd.y + 5); // +5 is a tolerance for alignment
                boolean isToRightOrSameCol = (td.x >= sd.x - 5); // +5 tolerance

                if(isDownward && isToRightOrSameCol) {
                    // Exit from right center of source
                    double sx = sd.x + sd.width;
                    double sy = sd.y + sd.height / 2;

                    // Enter target at left center if right, or at center if directly below
                    double tx = td.x;
                    double ty = td.y + td.height / 2;

                    // Horizontal (from right edge), vertical down, horizontal to target
                    double corridor = sx + cfg.horizontalGap*0.33; // corridorX;
                    // 1. right from source center
                    edge.addChildElement(createWaypoint(mi, sx, sy));
                    // 2. rightward horizontal to corridor
                    edge.addChildElement(createWaypoint(mi, corridor, sy));
                    // 3. vertical drop to align with target Y
                    edge.addChildElement(createWaypoint(mi, corridor, ty));
                    // 4. leftward to target left center
                    edge.addChildElement(createWaypoint(mi, tx, ty));
                    plane.addChildElement(edge);
                    continue;
                }

                // Special-case: direct vertical if in same column, adjacent rows, and no obstacles
                if (td.gridCol == sd.gridCol && !sameLaneFlow) {
                    int rowMin = Math.min(sd.gridRow, td.gridRow);
                    int rowMax = Math.max(sd.gridRow, td.gridRow);
                    boolean directVertical = true;
                    for (LayoutData ld : lm.values()) {
                        if (ld.gridCol == sd.gridCol && ld.gridRow > rowMin && ld.gridRow < rowMax) {
                            directVertical = false;
                            break;
                        }
                    }
                    if (directVertical) {
                        double sx = sd.x + sd.width / 2;
                        double sy = (td.y > sd.y) ? sd.y + sd.height : sd.y;
                        double ty = (td.y > sd.y) ? td.y : td.y + td.height;
                        edge.addChildElement(createWaypoint(mi, sx, sy));
                        edge.addChildElement(createWaypoint(mi, sx, ty));
                        plane.addChildElement(edge);
                        continue;
                    }
                }

                // Existing routes for other cases (left, up, etc)
                if(tgtCol < sd.gridCol){
                    double midY = sd.y + sd.height/2;
                    double belowT= td.y+td.height + cfg.verticalGap*0.2;
                    edge.addChildElement(createWaypoint(mi, sd.x+sd.width, midY));
                    edge.addChildElement(createWaypoint(mi, corridorX,    midY));
                    edge.addChildElement(createWaypoint(mi, corridorX,    belowT));
                    edge.addChildElement(createWaypoint(mi, td.x+td.width/2,belowT));
                    edge.addChildElement(createWaypoint(mi, td.x+td.width/2,td.y+td.height));
                }
                else if(sameLaneFlow){
                    double sy=sd.y+sd.height/2, ty=td.y+td.height/2;
                    edge.addChildElement(createWaypoint(mi, sd.x+sd.width, sy));
                    edge.addChildElement(createWaypoint(mi, td.x,         sy));
                    if(Math.abs(ty-sy)>1e-6) edge.addChildElement(createWaypoint(mi, td.x, ty));
                }
                else if(tgtCol==globalMaxCol){
                    double sy=sd.y+sd.height/2, ty=td.y+td.height/2;
                    edge.addChildElement(createWaypoint(mi, sd.x+sd.width, sy));
                    edge.addChildElement(createWaypoint(mi, td.x,         sy));
                    edge.addChildElement(createWaypoint(mi, td.x,         ty));
                }
                else {
                    boolean down = td.y>sd.y;
                    double sx=sd.x+sd.width/2;
                    double sy=down? sd.y+sd.height : sd.y;
                    double ty=td.y+td.height/2;
                    edge.addChildElement(createWaypoint(mi, sx, sy));
                    edge.addChildElement(createWaypoint(mi, sx, ty));
                    edge.addChildElement(createWaypoint(mi, td.x, ty));
                }

                plane.addChildElement(edge);
            }

            defs.addChildElement(diagram);
        }

        private static Waypoint createWaypoint(BpmnModelInstance mi,double x,double y){
            Waypoint w = mi.newInstance(Waypoint.class);
            w.setX(x); w.setY(y);
            return w;
        }
    }

    public static void main(String[] args) throws IOException {
        BpmnModelInstance model = Bpmn.createExecutableProcess("Process_1")
                .startEvent("startEvent_1").name("Start")
                .userTask("task_1").name("Receive Order")
                .exclusiveGateway("gateway_1").name("Decision")
                .userTask("task_2").name("Approve Order")
                .userTask("task_3").name("Reject Order")
                .userTask("task_4").name("Follow Order")
                .serviceTask("task_5").name("Purchase Order")
                .endEvent("endEvent_1").name("End")
                .done();

        Definitions defs = model.getDefinitions();
        defs.setTargetNamespace("http://camunda.org/examples");

        Collaboration coll = createElement(defs,"collaboration_1",Collaboration.class);
        coll.setName("Collaboration_1");
        Participant part = createElement(coll,"participant_1",Participant.class);
        part.setName("IST - Information Systems");
        part.setProcess(model.getModelElementById("Process_1"));

        Process proc = model.getModelElementById("Process_1");
        LaneSet ls = model.newInstance(LaneSet.class);
        ls.setId("laneSet_1");
        proc.addChildElement(ls);

        Lane sales   = model.newInstance(Lane.class);
        sales.setId("lane_sales");   sales.setName("Sales");
        Lane finance = model.newInstance(Lane.class);
        finance.setId("lane_finance");finance.setName("Finance");
        Lane purchase= model.newInstance(Lane.class);
        purchase.setId("lane_purchase");purchase.setName("Purchase");

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

        for(SequenceFlow f: new ArrayList<>(model.getModelElementsByType(SequenceFlow.class))){
            f.getParentElement().removeChildElement(f);
        }
        createSequenceFlow(model,"startEvent_1","task_1","flow_1","Start>Receive");
        createSequenceFlow(model,"task_1","gateway_1","flow_2","Receive>Decision");
        createSequenceFlow(model,"gateway_1","task_2","flow_3","Decision>Approve");
        createSequenceFlow(model,"gateway_1","task_3","flow_4","Decision>Reject");
        createSequenceFlow(model,"gateway_1","task_4","flow_5","Decision>Follow");
        createSequenceFlow(model,"gateway_1","task_5","flow_11","Decision>Purchase");
        createSequenceFlow(model,"task_2","endEvent_1","flow_6","Approve>End");
        createSequenceFlow(model,"task_3","task_1","flow_7","Reject>Receive");
        createSequenceFlow(model,"task_4","endEvent_1","flow_8","Follow>End");
        createSequenceFlow(model,"task_5","task_3","flow_9","Purchase>Reject");
        createSequenceFlow(model,"startEvent_1","task_5","flow_10","Start>Purchase");

        LayoutConfig cfg = new LayoutConfig();
        new AutoLayoutEngine().layout(model,cfg,
                Map.of(
                        sales, 0,
                        finance, 1,
                        purchase, 2
                ));

        try(FileWriter w = new FileWriter("src/main/resources/output/bpmn/testDiagramLayout.bpmn")){
            w.write(Bpmn.convertToString(model));
            System.out.println("Wrote target/testDiagramLayout.bpmn");
        }
    }

    private static <T extends BpmnModelElementInstance> T createElement(
            BpmnModelElementInstance parent, String id, Class<T> clazz) {
        T el = parent.getModelInstance().newInstance(clazz);
        el.setAttributeValue("id",id,true);
        parent.addChildElement(el);
        return el;
    }

    private static void createSequenceFlow(
            BpmnModelInstance model,
            String fromId, String toId,
            String flowId, String name)
    {
        FlowNode from = model.getModelElementById(fromId);
        FlowNode to   = model.getModelElementById(toId);
        Process proc  = model.getModelElementsByType(Process.class).iterator().next();

        SequenceFlow sf = model.newInstance(SequenceFlow.class);
        sf.setId(flowId);
        sf.setSource(from);
        sf.setTarget(to);
        sf.setName(name);
        proc.addChildElement(sf);
        from.getOutgoing().add(sf);
        to.getIncoming().add(sf);
    }
}

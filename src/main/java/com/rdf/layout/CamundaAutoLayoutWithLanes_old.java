package com.rdf.layout;

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

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Association;
import org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance;
import org.camunda.bpm.model.bpmn.instance.Collaboration;
import org.camunda.bpm.model.bpmn.instance.Definitions;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.FlowNode;
import org.camunda.bpm.model.bpmn.instance.Gateway;
import org.camunda.bpm.model.bpmn.instance.Lane;
import org.camunda.bpm.model.bpmn.instance.LaneSet;
import org.camunda.bpm.model.bpmn.instance.MessageFlow;
import org.camunda.bpm.model.bpmn.instance.Participant;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.TextAnnotation;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane;
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape;
import org.camunda.bpm.model.bpmn.instance.dc.Bounds;
import org.camunda.bpm.model.bpmn.instance.di.Waypoint;

public class CamundaAutoLayoutWithLanes_old {

  // -------------------------------------------------------------------------
  // Layout configuration: margins, gaps, default sizes
  // -------------------------------------------------------------------------
  public static class LayoutConfig {
    public double marginLeft     = 50;
    public double marginTop      = 50;
    public double horizontalGap  = 130;
    public double verticalGap    = 90;
    public double laneGap        = 0;
    public double annotationGap  = 30;

    /** how much extra space before column 0 (e.g. StartEvent) */
    public double column0Offset  = 30;
    /** horizontal space for lane label padding */
    public double laneLabelOffset = 30;
    /** extra vertical padding inside each lane */
    public double lanePadding    = 20;

    public double defaultWidth(String t) {
      switch (t.toLowerCase()) {
        case "startevent": case "endevent":         return 36;
        case "task": case "usertask":               return 100;
        case "servicetask": case "scripttask":      return 100;
        case "exclusivegateway":                    return 50;
        case "textannotation":                      return 100;
        default:                                    return 80;
      }
    }
    public double defaultHeight(String t) {
      switch (t.toLowerCase()) {
        case "startevent": case "endevent":         return 36;
        case "task": case "usertask":               return 80;
        case "servicetask": case "scripttask":      return 80;
        case "exclusivegateway":                    return 50;
        case "textannotation":                      return 30;
        default:                                    return 60;
      }
    }
  }

  // -------------------------------------------------------------------------
  // Holds grid indices plus computed x/y/width/height
  // -------------------------------------------------------------------------
  public static class LayoutData {
    public int gridRow, gridCol;
    public double x, y, width, height;
    public LayoutData(int r, int c) { gridRow = r; gridCol = c; }
  }

  // -------------------------------------------------------------------------
  // Core engine: grid assignment, absolute positioning, and DI injection
  // -------------------------------------------------------------------------
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
  // 0) Find process & laneSet
  Process proc = mi.getModelElementsByType(Process.class)
                   .stream().findFirst().orElse(null);
  if (proc == null) return;
  LaneSet ls = proc.getChildElementsByType(LaneSet.class)
                   .stream().findFirst().orElse(null);
  if (ls == null) return;

  // 1) Grid each lane
  List<Lane> lanes = new ArrayList<>(ls.getLanes());
  Map<Lane,Map<FlowNode,LayoutData>> byLane = new LinkedHashMap<>();
  Map<Lane,Integer> maxR = new HashMap<>();
  occupied.clear();
  globalLayout.clear();

  for (Lane lane : lanes) {
    Map<FlowNode,LayoutData> laneLayout = layoutLane(lane.getFlowNodeRefs(), cfg);
    byLane.put(lane, laneLayout);
    laneLayout.forEach(globalLayout::put);
    maxR.put(lane,
      laneLayout.values().stream().mapToInt(ld -> ld.gridRow).max().orElse(0));
  }

  // 2) Column widths & X positions
  int computedMaxCol = globalLayout.values().stream()
                         .mapToInt(ld -> ld.gridCol).max().orElse(0);
  double[] colW = new double[computedMaxCol + 1];
  globalLayout.forEach((n, ld) -> {
    ld.width = cfg.defaultWidth(n.getElementType().getTypeName());
    colW[ld.gridCol] = Math.max(colW[ld.gridCol], ld.width);
  });
  colX = new double[colW.length];
  colX[0] = cfg.marginLeft + cfg.column0Offset;
  for (int c = 1; c < colX.length; c++) {
    colX[c] = colX[c - 1] + colW[c - 1] + cfg.horizontalGap;
  }

  // 3) Bump EndEvents to column (computedMaxCol+1)
  int endCol = computedMaxCol + 1;
  double endX  = colX[computedMaxCol] + colW[computedMaxCol] + cfg.horizontalGap;
  double endW  = cfg.defaultWidth("EndEvent");
  mi.getModelElementsByType(EndEvent.class).forEach(end -> {
    LayoutData ld = globalLayout.get(end);
    if (ld != null) {
      ld.gridCol = endCol;
      ld.x       = endX;
      ld.width   = endW;
    }
  });

  // 4) Figure out the overall right most X so lanes/pool expand fully
  double globalRight = globalLayout.values().stream()
                          .mapToDouble(ld -> ld.x + ld.width)
                          .max()
                          .orElse(endX + endW);

  // 5) Compute Y positions and laneBounds
  double curY = cfg.marginTop;
  Map<Lane, Double[]> laneBounds = new LinkedHashMap<>();

  for (Lane lane : lanes) {
    Map<FlowNode,LayoutData> m = byLane.get(lane);
    if (m.isEmpty()) continue;

    // build row heights
    int maxRow = maxR.get(lane);
    double[] rowH = new double[maxRow + 1];
    m.forEach((n, ld) -> {
      ld.height = cfg.defaultHeight(n.getElementType().getTypeName());
      rowH[ld.gridRow] = Math.max(rowH[ld.gridRow], ld.height);
    });

    // Y for each row within this lane
    double[] rowY = new double[maxRow + 1];
    rowY[0] = curY;
    for (int r = 1; r <= maxRow; r++) {
      rowY[r] = rowY[r - 1] + rowH[r - 1] + cfg.verticalGap;
    }

    // assign x,y for each node
    m.forEach((n, ld) -> {
      if (!(n instanceof EndEvent)) {
        ld.x = colX[ld.gridCol]
             + (cfg.defaultWidth(n.getElementType().getTypeName()) - ld.width) / 2
             + cfg.laneLabelOffset;
      }
      ld.y = rowY[ld.gridRow]
           + (rowH[ld.gridRow] - ld.height) / 2;
    });

    // lane width spans from left margin to globalRight
    double laneW = (globalRight - cfg.marginLeft) + cfg.laneLabelOffset;
    // lane height = contentHeight + top/bottom padding
    double contentH = rowY[maxRow] + rowH[maxRow] - curY;
    double laneH    = contentH + cfg.lanePadding * 2;

    laneBounds.put(lane, new Double[]{
      cfg.marginLeft,
      curY - cfg.lanePadding,
      laneW,
      laneH
    });

    // <<< key change: only use laneGap between lanes, no verticalGap here >>>
    curY += contentH + cfg.lanePadding * 2 + cfg.laneGap;
  }

  // 6) Inject DI shapes & edges as before


  addBPMNDI(mi, globalLayout, laneBounds, cfg);
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

  // 1) Draw pool(s) for Participant(s)
  for (Participant p : mi.getModelElementsByType(Participant.class)) {
    BpmnShape poolShape = mi.newInstance(BpmnShape.class);
    poolShape.setId("Shape_" + p.getId());
    poolShape.setBpmnElement(p);

    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
    double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
    for (Double[] bb : bounds.values()) {
      minX = Math.min(minX, bb[0]);
      minY = Math.min(minY, bb[1]);
      maxX = Math.max(maxX, bb[0] + bb[2]);
      maxY = Math.max(maxY, bb[1] + bb[3]);
    }
    double px = minX;
    double py = minY;
    double pw = (maxX - minX) + cfg.column0Offset + cfg.laneLabelOffset;
    double ph = (maxY - minY);

    Bounds pb = mi.newInstance(Bounds.class);
    pb.setX(px); pb.setY(py);
    pb.setWidth(pw); pb.setHeight(ph);
    poolShape.setBounds(pb);
    plane.addChildElement(poolShape);
  }

  // 2) Draw each Lane shape inside its pool
  bounds.forEach((ln, bb) -> {
    BpmnShape laneShape = mi.newInstance(BpmnShape.class);
    laneShape.setId("Shape_" + ln.getId());
    laneShape.setBpmnElement(ln);

    Bounds lb = mi.newInstance(Bounds.class);
    lb.setX(bb[0] + cfg.laneLabelOffset);
    lb.setY(bb[1]);
    lb.setWidth(bb[2] + cfg.laneLabelOffset);
    lb.setHeight(bb[3]);
    laneShape.setBounds(lb);
    plane.addChildElement(laneShape);
  });

  // 3) Node shapes (tasks/gateways/events)
  lm.forEach((n, ld) -> {
    if (n instanceof TextAnnotation) return;
    BpmnShape s = mi.newInstance(BpmnShape.class);
    s.setId("Shape_" + n.getId());
    s.setBpmnElement(n);
    Bounds b = mi.newInstance(Bounds.class);
    b.setX(ld.x); b.setY(ld.y);
    b.setWidth(ld.width); b.setHeight(ld.height);
    s.setBounds(b);
    plane.addChildElement(s);
  });

  // 4) TextAnnotation shapes
  for (TextAnnotation ta : mi.getModelElementsByType(TextAnnotation.class)) {
    FlowNode target = null;
    for (Association as : mi.getModelElementsByType(Association.class)) {
      if (as.getSource().equals(ta) && as.getTarget() instanceof FlowNode) {
        target = (FlowNode) as.getTarget(); break;
      }
      if (as.getTarget().equals(ta) && as.getSource() instanceof FlowNode) {
        target = (FlowNode) as.getSource(); break;
      }
    }
    if (target == null) continue;
    LayoutData tld = lm.get(target);
    if (tld == null) continue;

    double w = cfg.defaultWidth("textannotation");
    double h = cfg.defaultHeight("textannotation");
    double x = tld.x + (tld.width - w)/2;
    double y = tld.y - cfg.annotationGap - h;

    BpmnShape sa = mi.newInstance(BpmnShape.class);
    sa.setId("Shape_" + ta.getId());
    sa.setBpmnElement(ta);
    Bounds bb = mi.newInstance(Bounds.class);
    bb.setX(x); bb.setY(y);
    bb.setWidth(w); bb.setHeight(h);
    sa.setBounds(bb);
    plane.addChildElement(sa);
  }

  // 5) SequenceFlow edges + labels
  for (SequenceFlow flow : mi.getModelElementsByType(SequenceFlow.class)) {
    LayoutData sd = lm.get(flow.getSource());
    LayoutData td = lm.get(flow.getTarget());
    if (sd == null || td == null) continue;

    BpmnEdge edge = mi.newInstance(BpmnEdge.class);
    edge.setId("Edge_" + flow.getId());
    edge.setBpmnElement(flow);

    Lane sL = getLane(flow.getSource()), tL = getLane(flow.getTarget());
    boolean sameLane = sL != null && sL.equals(tL);

    List<Waypoint> wps = new ArrayList<>();
    int tgtCol = td.gridCol;

    if (sameLane) {
  boolean isGatewaySource = flow.getSource() instanceof Gateway;

  if (isGatewaySource) {
    double tx = td.x;
    double ty = td.y + td.height / 2;

    // Sort outgoing flows by target Y to find the topmost
    List<SequenceFlow> outgoing = new ArrayList<>(flow.getSource().getOutgoing());
    outgoing.sort(Comparator.comparingDouble(f -> {
      LayoutData tld = lm.get(f.getTarget());
      return (tld != null) ? tld.y : Double.MAX_VALUE;
    }));

    boolean isFirstFlow = outgoing.get(0).getId().equals(flow.getId());

    if (isFirstFlow) {
      // Same logic as cross-lane topmost: right-side horizontal
      double sx = sd.x + sd.width;
      double sy = sd.y + sd.height / 2;
      wps.add(createWaypoint(mi, sx, sy));
      wps.add(createWaypoint(mi, tx, ty));
    } else {
      // Same as cross-lane: bottom-center → down → right
      double sx = sd.x + sd.width / 2;
      double sy = sd.y + sd.height;
      wps.add(createWaypoint(mi, sx, sy));  // down
      wps.add(createWaypoint(mi, sx, ty));  // vertical align with target
      wps.add(createWaypoint(mi, tx, ty));  // horizontal into target
    }
  } else {
    // Non-gateway default same-lane logic
    double sx = sd.x + sd.width;
    double sy = sd.y + sd.height / 2;
    double tx = td.x;
    double ty = td.y + td.height / 2;
    wps.add(createWaypoint(mi, sx, sy));
    wps.add(createWaypoint(mi, tx, sy));
    if (Math.abs(ty - sy) > 1e-6) {
      wps.add(createWaypoint(mi, tx, ty));
    }
  }
}

    else if (tgtCol == globalMaxCol) {
      // cross-lane into the far right column
      double sx = sd.x + sd.width;
      double sy = sd.y + sd.height/2;
      double tx = td.x;
      double ty = td.y + td.height/2;
      wps.add(createWaypoint(mi, sx, sy));
      wps.add(createWaypoint(mi, tx, sy));
      wps.add(createWaypoint(mi, tx, ty));
    }
    else {
      // all other cross-lane cases
      boolean down = td.y > sd.y;
      double sx = sd.x + sd.width/2;
      double sy = down ? (sd.y + sd.height) : sd.y;
      double tx = td.x;
      double ty = td.y + td.height/2;
      wps.add(createWaypoint(mi, sx, sy));
      wps.add(createWaypoint(mi, sx, ty));
      wps.add(createWaypoint(mi, tx, ty));
    }
    // ───────────────────────────────────

    // inject waypoints
    wps.forEach(edge::addChildElement);

    // label placement (unchanged)
    String name = flow.getName();
    if (name != null && !name.isEmpty()) {
      Waypoint A, B;
      if (wps.size() == 3) {
        if (td.y > sd.y) { A = wps.get(1); B = wps.get(2); }
        else if (td.y < sd.y) { A = wps.get(0); B = wps.get(1); }
        else { A = wps.get(0); B = wps.get(1); }
      } else {
        double best = -1; int idx = 0;
        for (int i = 0; i < wps.size() - 1; i++) {
          double dx = wps.get(i+1).getX() - wps.get(i).getX();
          double dy = wps.get(i+1).getY() - wps.get(i).getY();
          double len = Math.abs(dx) + Math.abs(dy);
          if (len > best) { best = len; idx = i; }
        }
        A = wps.get(idx); B = wps.get(idx+1);
      }
      double midX = (A.getX() + B.getX()) / 2;
      double midY = (A.getY() + B.getY()) / 2;
      double lw = cfg.defaultWidth("textannotation");
      double lh = cfg.defaultHeight("textannotation");
      double off = 12;
      double dx = B.getX() - A.getX();
      double dy = B.getY() - A.getY();
      double lx = midX - lw/2;
      double ly = midY - lh/2;
      if (Math.abs(dx) > Math.abs(dy)) {
        ly -= off;
      } else {
        lx += off;
      }
      org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnLabel lbl =
        mi.newInstance(org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnLabel.class);
      Bounds lb = mi.newInstance(Bounds.class);
      lb.setX(lx); lb.setY(ly);
      lb.setWidth(lw); lb.setHeight(lh);
      lbl.setBounds(lb);
      edge.addChildElement(lbl);
    }

    plane.addChildElement(edge);
  }

  // 6) MessageFlow edges (unchanged)
  for (MessageFlow mf : mi.getModelElementsByType(MessageFlow.class)) {
    LayoutData sd = lm.get((FlowNode)mf.getSource());
    LayoutData td = lm.get((FlowNode)mf.getTarget());
    if (sd==null||td==null) continue;
    BpmnEdge edge = mi.newInstance(BpmnEdge.class);
    edge.setId("Edge_"+mf.getId());
    edge.setBpmnElement(mf);
    edge.addChildElement(createWaypoint(mi,
      sd.x+sd.width/2, sd.y+sd.height/2));
    edge.addChildElement(createWaypoint(mi,
      td.x+td.width/2, td.y+td.height/2));
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

  // -------------------------------------------------------------------------
  // Example main for quick testing
  // -------------------------------------------------------------------------
  public static void main(String[] args) throws IOException {
    BpmnModelInstance model = Bpmn.createExecutableProcess("Process_1")
      .startEvent("startEvent_1").name("Start")
      .exclusiveGateway("gateway_1").name("Decision 1")
      .userTask("task_1").name("Receive Order")
      .exclusiveGateway("gateway_2").name("Decision 2")
      .userTask("task_2").name("Approve Order")
      .userTask("task_3").name("Reject Order")
      .userTask("task_4").name("Follow Order")
      .userTask("task_5").name("Review Order")
      .serviceTask("task_6").name("Purchase Order")
      .endEvent("endEvent_1").name("End")
      .done();

    Definitions definitions = model.getDefinitions();
    definitions.setTargetNamespace("http://camunda.org/examples");

    // Get process
    Process process = model.getModelElementById("Process_1");

    // Create Collaboration
    Collaboration collaboration = createElement(definitions, "collaboration_1", Collaboration.class);
    collaboration.setName("Collaboration_1");
    definitions.addChildElement(collaboration);

    // Create Participant and assign the process
    Participant participant = createElement(collaboration, "participant_1", Participant.class);
    participant.setName("IST - Information Systems");
    participant.setProcess(process);

    // Create LaneSet
    LaneSet laneSet = model.newInstance(LaneSet.class);
    laneSet.setId("laneSet_1");
    process.addChildElement(laneSet);

    // Create Lanes
    Lane sales = model.newInstance(Lane.class);
    sales.setId("lane_sales");
    sales.setName("Sales");

    Lane finance = model.newInstance(Lane.class);
    finance.setId("lane_finance");
    finance.setName("Finance");

    Lane purchase = model.newInstance(Lane.class);
    purchase.setId("lane_purchase");
    purchase.setName("Purchase");

    laneSet.addChildElement(sales);
    laneSet.addChildElement(finance);
    laneSet.addChildElement(purchase);

    // Assign flow nodes to lanes
    sales.getFlowNodeRefs().add(model.getModelElementById("startEvent_1"));
    sales.getFlowNodeRefs().add(model.getModelElementById("gateway_1"));
    sales.getFlowNodeRefs().add(model.getModelElementById("task_1"));
    sales.getFlowNodeRefs().add(model.getModelElementById("gateway_2"));
    sales.getFlowNodeRefs().add(model.getModelElementById("task_4"));
    sales.getFlowNodeRefs().add(model.getModelElementById("task_5"));
    sales.getFlowNodeRefs().add(model.getModelElementById("endEvent_1"));

    finance.getFlowNodeRefs().add(model.getModelElementById("task_2"));
    finance.getFlowNodeRefs().add(model.getModelElementById("task_3"));

    purchase.getFlowNodeRefs().add(model.getModelElementById("task_6"));

    // Remove default sequence flows and recreate manually
    for (SequenceFlow f : new ArrayList<>(model.getModelElementsByType(SequenceFlow.class))) {
        f.getParentElement().removeChildElement(f);
    }
    createSequenceFlow(model, "startEvent_1", "gateway_1", "flow_1", "Start to Decision 1");
    createSequenceFlow(model, "gateway_1", "task_1", "flow_2", "Decision 1 to Receive Order");
    createSequenceFlow(model, "gateway_1", "task_6", "flow_3", "Decision 1 to Purchase Order");
    createSequenceFlow(model, "task_1", "gateway_2", "flow_4", "Receive Order to Decision 2");
    createSequenceFlow(model, "gateway_2", "task_2", "flow_5", "Decision 2 to Approve Order");
    createSequenceFlow(model, "gateway_2", "task_3", "flow_6", "Decision 2 to Reject Order");
    createSequenceFlow(model, "gateway_2", "task_4", "flow_7", "Decision 2 to Follow Order");
    createSequenceFlow(model, "gateway_2", "task_5", "flow_8", "Decision 2 to Review Order");
    createSequenceFlow(model, "task_2", "endEvent_1", "flow_9", "Approve Order to End");
    createSequenceFlow(model, "task_3", "endEvent_1", "flow_10", "Reject Order to End");
    createSequenceFlow(model, "task_4", "endEvent_1", "flow_11", "Follow Order to End");
    createSequenceFlow(model, "task_5", "endEvent_1", "flow_12", "Review Order to End");
    createSequenceFlow(model, "task_6", "endEvent_1", "flow_13", "Purchase Order to End");
    // cycles in bpmn
    createSequenceFlow(model, "gateway_2", "gateway_1", "flow_14", "Decision 2 to Decision 1");
    createSequenceFlow(model, "task_3", "task_1", "flow_15", "Reject Order to Receive Order");
    createSequenceFlow(model, "task_4", "task_1", "flow_16", "Follow Order to Receive Order");

    // Optional layout
    LayoutConfig cfg = new LayoutConfig();
    new AutoLayoutEngine().layout(model, cfg);

    // Write to file
    try (FileWriter w = new FileWriter("src/main/resources/output/bpmn/testDiagramLayout.bpmn")) {
        w.write(Bpmn.convertToString(model));
    }
}

// Helper method to create elements
private static <T extends BpmnModelElementInstance> T createElement(BpmnModelElementInstance parentElement, String id, Class<T> elementClass) {
    T element = parentElement.getModelInstance().newInstance(elementClass);
    element.setAttributeValue("id", id, true);
    parentElement.addChildElement(element);
    return element;
}

// Helper method to create sequence flows
private static void createSequenceFlow(BpmnModelInstance model, String fromId, String toId, String flowId, String name) {
  FlowNode from = model.getModelElementById(fromId);
  FlowNode to = model.getModelElementById(toId);
  Process process = model.getModelElementsByType(Process.class).iterator().next();
  
  // Create the sequence flow
  SequenceFlow flow = model.newInstance(SequenceFlow.class);
  flow.setId(flowId);
  flow.setSource(from);
  flow.setTarget(to);
  flow.setName(name);

  process.addChildElement(flow);

  // Now set it as outgoing/incoming
  from.getOutgoing().add(flow);
  to.getIncoming().add(flow);
}

}
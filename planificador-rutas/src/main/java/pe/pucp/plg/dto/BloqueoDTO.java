package pe.pucp.plg.dto;

import java.awt.Point;
import java.util.List;

public class BloqueoDTO {
    private int startMin;
    private int endMin;
    private List<Point> nodes;

    public int getStartMin() { return startMin; }
    public void setStartMin(int startMin) { this.startMin = startMin; }

    public int getEndMin() { return endMin; }
    public void setEndMin(int endMin) { this.endMin = endMin; }

    public List<Point> getNodes() { return nodes; }
    public void setNodes(List<Point> nodes) { this.nodes = nodes; }
}

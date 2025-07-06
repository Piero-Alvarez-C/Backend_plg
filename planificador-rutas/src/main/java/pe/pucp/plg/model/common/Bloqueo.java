package pe.pucp.plg.model.common;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Bloqueo {
    private LocalDateTime startTime;  // tiempo de inicio
    private LocalDateTime endTime;    // tiempo de fin (exclusivo)
    private final List<Point> nodes; // nodos extremos del bloqueo (poligonal abierta)
    private String description;
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)d(\\d+)h(\\d+)m");

    public Bloqueo(LocalDateTime startTime, LocalDateTime endTime, List<Point> nodes) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.nodes = new ArrayList<>(nodes);
    }

    /** Construye un Bloqueo a partir de una línea de tu archivo:
     *  "01d06h00m-01d15h00m:31,21,34,21,..." */
    public static Bloqueo fromRecord(String record, LocalDateTime baseDateTime) {
        String[] parts = record.split(":");
        String[] times = parts[0].split("-");
        int startMin = parseTimeToMinutes(times[0]);
        int endMin = parseTimeToMinutes(times[1]);
        
        LocalDateTime startTime = baseDateTime.plusMinutes(startMin);
        LocalDateTime endTime = baseDateTime.plusMinutes(endMin);

        String[] coords = parts[1].split(",");
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            int x = Integer.parseInt(coords[i]);
            int y = Integer.parseInt(coords[i+1]);
            pts.add(new Point(x, y));
        }
        return new Bloqueo(startTime, endTime, pts);
    }

    /** ¿Está activo en el tiempo t? */
    public boolean isActiveAt(LocalDateTime t) {
        return !t.isBefore(startTime) && t.isBefore(endTime);
    }

    /** ¿Ese punto p está bloqueado en el tiempo t? */
    public boolean estaBloqueado(LocalDateTime t, Point p) {
        if (t.isBefore(startTime) || !t.isBefore(endTime)) return false;
        // Recorro cada segmento de la poligonal
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i), b = nodes.get(i+1);
            // Compruebo si p está sobre el segmento a–b
            if (a.x == b.x && p.x == a.x &&
                    p.y >= Math.min(a.y,b.y) && p.y <= Math.max(a.y,b.y)) {
                return true;
            }
            if (a.y == b.y && p.y == a.y &&
                    p.x >= Math.min(a.x,b.x) && p.x <= Math.max(a.x,b.x)) {
                return true;
            }
        }
        return false;
    }

    /** ¿Bloquea el segmento p→q?
     *  Compara con cada par consecutivo en this.nodes */
    public boolean coversSegment(Point p, Point q) {
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i);
            Point b = nodes.get(i + 1);
            // coincide en cualquier orden
            if ((p.equals(a) && q.equals(b)) ||
                    (p.equals(b) && q.equals(a))) {
                return true;
            }
        }
        return false;
    }

    /** Convierte “#d#h#m” a minutos totales */
    private static int parseTimeToMinutes(String s) {
        Matcher m = TIME_PATTERN.matcher(s);
        if (!m.matches()) {
            throw new IllegalArgumentException("Formato de tiempo inválido: " + s);
        }
        int days    = Integer.parseInt(m.group(1));
        int hours   = Integer.parseInt(m.group(2));
        int minutes = Integer.parseInt(m.group(3));
        return days * 24 * 60 + hours * 60 + minutes;
    }

    // Getters (si los necesitas)
    public LocalDateTime getStartTime() {
        return startTime;
    }
    public LocalDateTime getEndTime() {
        return endTime;
    }
    public List<Point> getNodes() {
        return Collections.unmodifiableList(nodes);
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public String getDescription() {
        return description;
    }
    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }
    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }
}

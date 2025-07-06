package pe.pucp.plg.service.Impl;

import org.springframework.stereotype.Service;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.context.ExecutionContext;
import pe.pucp.plg.service.BloqueoService;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;

@Service
public class BloqueoServiceImpl implements BloqueoService {
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d+)d(\\d+)h(\\d+)m");
    @Override
    public boolean estaActivo(Bloqueo b, LocalDateTime tiempo) {
        return !tiempo.isBefore(b.getStartTime()) && tiempo.isBefore(b.getEndTime());
    }

    @Override
    public boolean cubrePunto(Bloqueo b, LocalDateTime tiempo, Point p) {
        if (!estaActivo(b, tiempo)) return false;
        List<Point> nodes = b.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i), q = nodes.get(i + 1);
            if (a.x == q.x && p.x == a.x && p.y >= Math.min(a.y, q.y) && p.y <= Math.max(a.y, q.y)) return true;
            if (a.y == q.y && p.y == a.y && p.x >= Math.min(a.x, q.x) && p.x <= Math.max(a.x, q.x)) return true;
        }
        return false;
    }

    @Override
    public boolean cubreSegmento(Bloqueo b, Point p, Point q) {
        List<Point> nodes = b.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            Point a = nodes.get(i), b2 = nodes.get(i + 1);
            if ((p.equals(a) && q.equals(b2)) || (p.equals(b2) && q.equals(a))) return true;
        }
        return false;
    }

    @Override
    public Bloqueo parseDesdeLinea(String linea) {
        String[] parts = linea.split(":");
        String[] times = parts[0].split("-");
        int s = parseTimeToMinutes(times[0]);
        int e = parseTimeToMinutes(times[1]);

        String[] coords = parts[1].split(",");
        List<Point> pts = new ArrayList<>();
        for (int i = 0; i < coords.length; i += 2) {
            int x = Integer.parseInt(coords[i]);
            int y = Integer.parseInt(coords[i+1]);
            pts.add(new Point(x, y));
        }

        // Create base date as Jan 1, 2025 (or any appropriate base date)
        LocalDateTime baseDateTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime startTime = baseDateTime.plusMinutes(s);
        LocalDateTime endTime = baseDateTime.plusMinutes(e);
        
        return new Bloqueo(startTime, endTime, pts);
    }

    private int parseTimeToMinutes(String s) {
        Matcher m = TIME_PATTERN.matcher(s);
        if (!m.matches()) throw new IllegalArgumentException("Formato inválido: " + s);
        int d = Integer.parseInt(m.group(1));
        int h = Integer.parseInt(m.group(2));
        int mnt = Integer.parseInt(m.group(3));
        return (d-1) * 1440 + h * 60 + mnt;
    }

    @Override
    public List<Bloqueo> listarTodos(ExecutionContext context) {
        return context.getBloqueos();
    }
}

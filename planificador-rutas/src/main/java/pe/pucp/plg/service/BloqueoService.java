package pe.pucp.plg.service;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.context.ExecutionContext;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;

public interface BloqueoService {
    boolean estaActivo(Bloqueo b, LocalDateTime tiempo);
    boolean cubrePunto(Bloqueo b, LocalDateTime tiempo, Point p);
    boolean cubreSegmento(Bloqueo b, Point p, Point q);
    Bloqueo parseDesdeLinea(String linea);
    List<Bloqueo> listarTodos(ExecutionContext context);
}

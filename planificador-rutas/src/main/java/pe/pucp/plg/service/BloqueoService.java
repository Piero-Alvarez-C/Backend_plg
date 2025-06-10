package pe.pucp.plg.service;

import pe.pucp.plg.model.Bloqueo;

import java.util.*;
import java.awt.*;
import java.util.List;

public interface BloqueoService {
    boolean estaActivo(Bloqueo b, int tiempo);
    boolean cubrePunto(Bloqueo b, int tiempo, Point p);
    boolean cubreSegmento(Bloqueo b, Point p, Point q);
    Bloqueo parseDesdeLinea(String linea);
    List<Bloqueo> listarTodos();
}

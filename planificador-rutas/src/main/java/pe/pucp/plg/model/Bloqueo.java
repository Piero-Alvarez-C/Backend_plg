package pe.pucp.plg.model;

import java.time.LocalDateTime;
import java.util.List;

public class Bloqueo {
    private LocalDateTime inicio;
    private LocalDateTime fin;
    private List<Coordenada> nodos;

    public Bloqueo() {}

    public Bloqueo(LocalDateTime inicio, LocalDateTime fin, List<Coordenada> nodos) {
        this.inicio = inicio;
        this.fin = fin;
        this.nodos = nodos;
    }

    public LocalDateTime getInicio() {
        return inicio;
    }

    public void setInicio(LocalDateTime inicio) {
        this.inicio = inicio;
    }

    public LocalDateTime getFin() {
        return fin;
    }

    public void setFin(LocalDateTime fin) {
        this.fin = fin;
    }

    public List<Coordenada> getNodos() {
        return nodos;
    }

    public void setNodos(List<Coordenada> nodos) {
        this.nodos = nodos;
    }
}

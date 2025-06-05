package pe.pucp.plg.model;

import java.util.ArrayList;
import java.util.List;

public class Ruta {
    public CamionEstado estadoCamion;
    public List<Integer> pedidos = new ArrayList<>();
    public double distancia = 0;
    public double consumo = 0;
}

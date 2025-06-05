package pe.pucp.plg.util;

import pe.pucp.plg.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ParseadorArchivos {

    // 🟢 Adaptado a tu clase Pedido actual
    public static List<Pedido> parsearPedidos(String contenido) {
        List<Pedido> pedidos = new ArrayList<>();
        int id = 1;
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                String[] partes = linea.split(":");
                String tiempoTexto = partes[0]; // Ej: 00d00h24m
                String[] datos = partes[1].split(",");

                int dia = Integer.parseInt(tiempoTexto.substring(0, 2));
                int hora = Integer.parseInt(tiempoTexto.substring(3, 5));
                int minuto = Integer.parseInt(tiempoTexto.substring(6, 8));
                int tiempoCreacion = dia * 1440 + hora * 60 + minuto;

                int x = Integer.parseInt(datos[0]);
                int y = Integer.parseInt(datos[1]);
                int volumen = Integer.parseInt(datos[3].replace("m3", ""));
                int plazoHoras = Integer.parseInt(datos[4].replace("h", ""));
                int tiempoLimite = tiempoCreacion + plazoHoras * 60;

                pedidos.add(new Pedido(id++, tiempoCreacion, x, y, volumen, tiempoLimite));
            } catch (Exception e) {
                System.err.println("⚠️ Error parseando línea PEDIDO: " + linea);
            }
        }
        return pedidos;
    }

    // 🟡 Solo placeholder si vas a usar mantenimientos luego
    public static List<Mantenimiento> parsearMantenimientos(String contenido) {
        return new ArrayList<>();
    }

    // 🔴 Adaptado a tu clase Bloqueo actual (usa enteros, Point)
    public static List<Bloqueo> parsearBloqueos(String contenido) {
        List<Bloqueo> bloqueos = new ArrayList<>();
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                bloqueos.add(Bloqueo.fromRecord(linea));
            } catch (Exception e) {
                System.err.println("⚠️ Error parseando línea BLOQUEO: " + linea);
            }
        }
        return bloqueos;
    }

    // 🟡 Solo placeholder si vas a usar averías luego
    public static List<Averia> parsearAverias(String contenido) {
        return new ArrayList<>();
    }
}
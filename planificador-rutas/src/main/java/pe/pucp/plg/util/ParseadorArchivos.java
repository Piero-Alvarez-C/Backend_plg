package pe.pucp.plg.util;

import pe.pucp.plg.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class ParseadorArchivos {

    public static List<Pedido> parsearPedidos(String contenido) {
        List<Pedido> pedidos = new ArrayList<>();
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                String[] partes = linea.split(":");
                String fechaTexto = partes[0]; // Ej: 00d00h24m
                String[] datos = partes[1].split(",");

                int dia = Integer.parseInt(fechaTexto.substring(0, 2));
                int hora = Integer.parseInt(fechaTexto.substring(3, 5));
                int minuto = Integer.parseInt(fechaTexto.substring(6, 8));
                LocalDateTime fecha = LocalDateTime.of(2025, 1, 1, 0, 0)
                        .plusDays(dia)
                        .plusHours(hora)
                        .plusMinutes(minuto);

                int posX = Integer.parseInt(datos[0]);
                int posY = Integer.parseInt(datos[1]);
                String idCliente = datos[2];
                int volumen = Integer.parseInt(datos[3].replace("m3", ""));
                int plazo = Integer.parseInt(datos[4].replace("h", ""));

                pedidos.add(new Pedido(fecha, posX, posY, idCliente, volumen, plazo));
            } catch (Exception e) {
                System.err.println("Error parseando línea PEDIDO: " + linea);
            }
        }
        return pedidos;
    }

    public static List<Mantenimiento> parsearMantenimientos(String contenido) {
        List<Mantenimiento> mantenimientos = new ArrayList<>();
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                String[] partes = linea.split(":");
                LocalDate fecha = LocalDate.parse(partes[0], DateTimeFormatter.ofPattern("yyyyMMdd"));
                String vehiculo = partes[1];
                mantenimientos.add(new Mantenimiento(fecha, vehiculo));
            } catch (Exception e) {
                System.err.println("Error parseando línea MANTENIMIENTO: " + linea);
            }
        }
        return mantenimientos;
    }

    public static List<Bloqueo> parsearBloqueos(String contenido) {
        List<Bloqueo> bloqueos = new ArrayList<>();
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                String[] partes = linea.split(":");
                String[] tiempo = partes[0].split("-");
                LocalDateTime inicio = parseFechaSimulada(tiempo[0]);
                LocalDateTime fin = parseFechaSimulada(tiempo[1]);

                String[] coords = partes[1].split(",");
                List<Coordenada> nodos = new ArrayList<>();
                for (int i = 0; i < coords.length; i += 2) {
                    nodos.add(new Coordenada(
                            Integer.parseInt(coords[i]),
                            Integer.parseInt(coords[i + 1])
                    ));
                }
                bloqueos.add(new Bloqueo(inicio, fin, nodos));
            } catch (Exception e) {
                System.err.println("Error parseando línea BLOQUEO: " + linea);
            }
        }
        return bloqueos;
    }

    public static List<Averia> parsearAverias(String contenido) {
        List<Averia> averias = new ArrayList<>();
        for (String linea : contenido.split("\\r?\\n")) {
            try {
                String[] partes = linea.split("_");
                String turno = partes[0];
                String vehiculo = partes[1];
                String tipo = partes[2];
                averias.add(new Averia(turno, vehiculo, tipo));
            } catch (Exception e) {
                System.err.println("Error parseando línea AVERÍA: " + linea);
            }
        }
        return averias;
    }

    private static LocalDateTime parseFechaSimulada(String bloque) {
        int dia = Integer.parseInt(bloque.substring(0, 2));
        int hora = Integer.parseInt(bloque.substring(3, 5));
        int minuto = Integer.parseInt(bloque.substring(6, 8));
        return LocalDateTime.of(2025, 1, 1, 0, 0)
                .plusDays(dia)
                .plusHours(hora)
                .plusMinutes(minuto);
    }
}

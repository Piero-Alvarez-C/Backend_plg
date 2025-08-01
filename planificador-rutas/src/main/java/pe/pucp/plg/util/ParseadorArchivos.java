package pe.pucp.plg.util;

import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.service.Impl.BloqueoServiceImpl;
import pe.pucp.plg.model.common.Averia;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


public class ParseadorArchivos {

    // 🟢 Adaptado para usar LocalDateTime
    public static List<Pedido> parsearPedidos(String contenido, LocalDate fechaPrimerDiaDelMes) {
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
                
                // Crear LocalDateTime preciso para el momento de creación
                LocalDateTime tiempoCreacion = fechaPrimerDiaDelMes.plusDays(dia - 1).atTime(hora, minuto);

                int x = Integer.parseInt(datos[0]);
                int y = Integer.parseInt(datos[1]);
                int volumen = Integer.parseInt(datos[3].replace("m3", ""));
                int plazoHoras = Integer.parseInt(datos[4].replace("h", ""));
                
                // Calcular el tiempo límite sumando las horas de plazo
                LocalDateTime tiempoLimite = tiempoCreacion.plusHours(plazoHoras);

                pedidos.add(new Pedido(String.valueOf(id), tiempoCreacion, x, y, volumen, tiempoLimite));
                id++;
            } catch (Exception e) {
                System.err.println("⚠️ Error parseando línea PEDIDO: " + linea);
                e.printStackTrace();
            }
        }
        return pedidos;
    }

    // 🟡 Solo placeholder si vas a usar mantenimientos luego
    public static Map<LocalDate, String> parsearMantenimientos(String contenido) {
        Map<LocalDate, String> mantenimientos = new HashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        for (String linea : contenido.split("\\R")) {
            if (linea.isBlank()) continue;
            try {
                String[] partes = linea.split(":");
                LocalDate fecha = LocalDate.parse(partes[0], formatter);
                String camionId = partes[1];
                mantenimientos.put(fecha, camionId);
            } catch (Exception e) {
                System.err.println("⚠️ Error parseando línea de mantenimiento: " + linea);
                e.printStackTrace();
            }
        }
        System.out.printf("✅ Cargados %d mantenimientos programados.%n", mantenimientos.size());
        return mantenimientos;  
    }

    public static List<Bloqueo> parsearBloqueos(String contenido, LocalDate fechaPrimerDiaDelMes) {
        // Ejemplo sencillo: cada línea: startMin-endMin:x1,y1,x2,y2,x3,y3,...
        //   0d0h0m-0d0h10m:5,5,5,6,5,7
        List<Bloqueo> lista = new ArrayList<>();
        for (String linea : contenido.split("\\R")) {
            if (linea.isBlank()) continue;
            // Usa el parse que ya tienes en tu BloqueoServiceImpl.parseDesdeLinea()
            // Por simplicidad:
            Bloqueo b = new BloqueoServiceImpl().parseDesdeLinea(linea, fechaPrimerDiaDelMes);
            b.setDescription(b.getStartTime() + "-" + b.getEndTime());
            lista.add(b);
        }
        return lista;
    }
    /**
     * Agrupa los pedidos por su tiempo de creación.
     */
    public static NavigableMap<LocalDateTime, List<Pedido>> parsearPedidosPorTiempo(String contenido, LocalDate fechaPrimerDiaDelMes) {
        return parsearPedidos(contenido, fechaPrimerDiaDelMes).stream()
                .collect(Collectors.groupingBy(
                    Pedido::getTiempoCreacion,
                    TreeMap::new,
                    Collectors.toList()
                ));
    }

    public static Map<String, Map<String, Averia>> parsearAverias(String contenido) {
        Map<String, Map<String, Averia>> resultado = new HashMap<>();

        for (String linea : contenido.split("\\R")) {
            if (linea.isBlank()) continue;

            try {
                String[] partes = linea.split("_");
                if (partes.length != 3) {
                    System.err.println("⚠️ Formato incorrecto en línea de avería: " + linea);
                    continue;
                }

                String turno = partes[0].trim();         // T1, T2, T3
                String camionId = partes[1].trim();      // CAM001, etc.
                String tipoIncidente = partes[2].trim(); // TI1, TI2, etc.

                Averia averia = new Averia(turno, camionId, tipoIncidente, true);

                resultado
                        .computeIfAbsent(turno, k -> new HashMap<>())
                        .put(camionId, averia);

            } catch (Exception e) {
                System.err.println("❌ Error parseando avería: " + linea + " → " + e.getMessage());
            }
        }

        return resultado;
    }


}
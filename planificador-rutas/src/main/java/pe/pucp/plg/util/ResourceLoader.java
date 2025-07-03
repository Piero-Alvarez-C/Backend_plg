package pe.pucp.plg.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Pedido;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utilidad para cargar recursos desde el classpath según la fecha.
 */
public class ResourceLoader {

    /**
     * Carga los pedidos correspondientes a una fecha específica.
     * 
     * @param fecha La fecha para la cual cargar los pedidos
     * @return Lista de pedidos filtrados para el día exacto de la fecha
     */
    public static List<Pedido> cargarPedidosParaFecha(LocalDate fecha) {
        try {
            // Formatear la fecha para obtener el año y mes (ej. 2025-07-15 -> "2507")
            String anioMes = fecha.format(DateTimeFormatter.ofPattern("yyMM"));
            
            // Construir el nombre del archivo
            String nombreArchivo = "pedidos/ventas" + anioMes + ".txt";
            
            // Cargar el contenido desde resources
            String contenido = leerContenidoArchivo(nombreArchivo);
            
            // Parsear todos los pedidos del archivo
            List<Pedido> todosLosPedidos = ParseadorArchivos.parsearPedidos(contenido);
            
            // Calcular minutos desde inicio de mes para el día específico
            int diaDelMes = fecha.getDayOfMonth();
            int minutosInicioDia = (diaDelMes - 1) * 1440; // 1440 minutos por día
            int minutosFinDia = diaDelMes * 1440;
            
            // Filtrar para obtener solo pedidos del día específico
            return todosLosPedidos.stream()
                .filter(p -> p.getTiempoCreacion() >= minutosInicioDia && p.getTiempoCreacion() < minutosFinDia)
                .collect(Collectors.toList());
            
        } catch (IOException e) {
            System.err.println("Error cargando pedidos para fecha " + fecha + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Carga los bloqueos correspondientes a una fecha específica.
     * 
     * @param fecha La fecha para la cual cargar los bloqueos
     * @return Lista de bloqueos que están activos en la fecha indicada
     */
    public static List<Bloqueo> cargarBloqueosParaFecha(LocalDate fecha) {
        try {
            // Formatear la fecha para obtener el año y mes (ej. 2025-07-15 -> "2507")
            String anioMes = fecha.format(DateTimeFormatter.ofPattern("yyMM"));
            
            // Construir el nombre del archivo
            String nombreArchivo = anioMes + ".bloqueos.txt";
            
            // Cargar el contenido desde resources
            String contenido = leerContenidoArchivo(nombreArchivo);
            
            // Parsear todos los bloqueos del archivo
            List<Bloqueo> todosLosBloqueos = ParseadorArchivos.parsearBloqueos(contenido);
            
            // Calcular minutos desde inicio de mes para el día específico
            int diaDelMes = fecha.getDayOfMonth();
            int minutosInicioDia = (diaDelMes - 1) * 1440; // 1440 minutos por día
            int minutosFinDia = diaDelMes * 1440;
            
            // Filtrar para obtener solo bloqueos activos en el día específico
            return todosLosBloqueos.stream()
                .filter(b -> (b.getStartMin() < minutosFinDia && b.getEndMin() > minutosInicioDia))
                .collect(Collectors.toList());
            
        } catch (IOException e) {
            System.err.println("Error cargando bloqueos para fecha " + fecha + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * Método auxiliar para leer el contenido de un archivo desde resources.
     * 
     * @param nombreArchivo Nombre del archivo a leer
     * @return Contenido del archivo como String
     * @throws IOException Si hay error al leer el archivo
     */
    private static String leerContenidoArchivo(String nombreArchivo) throws IOException {
        ClassPathResource resource = new ClassPathResource(nombreArchivo);
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }
}

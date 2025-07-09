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

/**
 * Utilidad para cargar recursos desde el classpath según la fecha.
 */
public class ResourceLoader {

    /**
     * Carga los pedidos correspondientes a una fecha específica.
     * 
     * @param fecha La fecha para la cual cargar los pedidos
     * @return Lista de pedidos para la fecha especificada
     */
    public static List<Pedido> cargarPedidosParaFecha(LocalDate fecha) {
        try {
            // Formatear la fecha para obtener el año y mes (ej. 2025-07-15 -> "2507")
            String anioMes = fecha.format(DateTimeFormatter.ofPattern("yyMM"));
            
            // Construir el nombre del archivo
            String nombreArchivo = "pedidos/ventas20" + anioMes + ".txt";
            
            // Cargar el contenido desde resources
            String contenido = leerContenidoArchivo(nombreArchivo);
            
            // Calcular el primer día del mes para convertir a LocalDateTime absoluto
            LocalDate primerDiaDelMes = fecha.withDayOfMonth(1);
            
            // Parsear todos los pedidos del archivo con fechas absolutas
            List<Pedido> todosLosPedidos = ParseadorArchivos.parsearPedidos(contenido, primerDiaDelMes);
            
            // Filtrar para obtener solo pedidos del día específico
            // El filtro ahora es más simple porque trabajamos con LocalDateTime completo
            LocalDate fechaSiguiente = fecha.plusDays(1);
            
            return todosLosPedidos.stream()
                .filter(p -> !p.getTiempoCreacion().toLocalDate().isBefore(fecha) && 
                             p.getTiempoCreacion().toLocalDate().isBefore(fechaSiguiente))
                .toList();
            
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
            String nombreArchivo = "bloqueos/20" + anioMes + ".bloqueos.txt";
            
            // Cargar el contenido desde resources
            String contenido = leerContenidoArchivo(nombreArchivo);
            
            // Calcular el primer día del mes para convertir a LocalDateTime absoluto
            LocalDate primerDiaDelMes = fecha.withDayOfMonth(1);
            
            // Parsear todos los bloqueos del archivo con fechas absolutas
            List<Bloqueo> todosLosBloqueos;
            try {
                todosLosBloqueos = ParseadorArchivos.parsearBloqueos(contenido, primerDiaDelMes);
                System.out.println("Parseados " + todosLosBloqueos.size() + " bloqueos del archivo " + nombreArchivo);
            } catch (Exception e) {
                System.err.println("Error al parsear bloqueos del archivo " + nombreArchivo + ": " + e.getMessage());
                e.printStackTrace();
                return Collections.emptyList();
            }
            
            // Filtrar para obtener solo bloqueos que tienen efecto en el día específico
            // El filtro ahora es más simple porque trabajamos con LocalDateTime completo
            LocalDate fechaSiguiente = fecha.plusDays(1);

            List<Bloqueo> bloqueosFiltrados = todosLosBloqueos.stream()
                .filter(b -> b != null && b.getStartTime() != null && b.getEndTime() != null)
                .filter(b -> !b.getStartTime().toLocalDate().isAfter(fechaSiguiente.minusDays(1)) && 
                             !b.getEndTime().toLocalDate().isBefore(fecha))
                .toList();
                
            System.out.println("Filtrados " + bloqueosFiltrados.size() + " bloqueos para fecha " + fecha);
            return bloqueosFiltrados;
            
        } catch (IOException e) {
            System.err.println("Error cargando bloqueos para fecha " + fecha + ": " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        } catch (Exception e) {
            System.err.println("Error inesperado cargando bloqueos para fecha " + fecha + ": " + e.getMessage());
            e.printStackTrace();
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

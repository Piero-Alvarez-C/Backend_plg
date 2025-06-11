package pe.pucp.plg.service.Impl;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.enums.ArchivoTipo;
import pe.pucp.plg.model.common.Bloqueo;
import pe.pucp.plg.model.common.Mantenimiento;
import pe.pucp.plg.model.common.Pedido;
import pe.pucp.plg.service.ArchivoService;
import pe.pucp.plg.service.PlanificadorService;
import pe.pucp.plg.util.ParseadorArchivos;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ArchivoServiceImpl implements ArchivoService {

    // Almacenamiento en memoria de archivos subidos
    private final Map<String, byte[]> archivosTemporales = new ConcurrentHashMap<>();
    private final PlanificadorService planificadorService;

    public ArchivoServiceImpl(PlanificadorService planificadorService) {
        this.planificadorService = planificadorService;
    }

    @Override
    public void procesarArchivo(MultipartFile archivo, ArchivoTipo tipo) {
        try {
            String contenido = new String(archivo.getBytes());

            switch (tipo) {
                case PEDIDOS -> {
                    List<Pedido> pedidos = ParseadorArchivos.parsearPedidos(contenido);
                    planificadorService.setPedidos(pedidos);
                }
                case MANTENIMIENTOS -> {
                    List<Mantenimiento> mantenimientos = ParseadorArchivos.parsearMantenimientos(contenido);
                    planificadorService.setMantenimientos(mantenimientos);
                }
                case BLOQUEOS -> {
                    List<Bloqueo> bloqueos = ParseadorArchivos.parsearBloqueos(contenido);
                    planificadorService.setBloqueos(bloqueos);
                }
                default -> throw new IllegalArgumentException("Tipo de archivo no soportado");
            }

            System.out.println("Archivo " + tipo + " procesado y enviado al planificador.");

        } catch (IOException e) {
            throw new RuntimeException("Error leyendo archivo: " + e.getMessage());
        }
    }

    @Override
    public String guardarArchivoTemporal(MultipartFile archivo) throws Exception {
        if (archivo == null || archivo.isEmpty()) {
            throw new IllegalArgumentException("Archivo vacío o nulo.");
        }

        String fileId = UUID.randomUUID().toString();
        archivosTemporales.put(fileId, archivo.getBytes());
        return fileId;
    }

    @Override
    public byte[] obtenerArchivo(String fileId) throws Exception {
        byte[] contenido = archivosTemporales.get(fileId);
        if (contenido == null) {
            throw new IllegalArgumentException("Archivo no encontrado con ID: " + fileId);
        }
        return contenido;
    }
}

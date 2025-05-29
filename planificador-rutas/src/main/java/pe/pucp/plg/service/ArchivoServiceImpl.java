package pe.pucp.plg.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.ArchivoTipo;
import pe.pucp.plg.model.*;
import pe.pucp.plg.util.ParseadorArchivos;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ArchivoServiceImpl implements ArchivoService {

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
                case AVERIAS -> {
                    List<Averia> averias = ParseadorArchivos.parsearAverias(contenido);
                    planificadorService.setAverias(averias);
                }
                default -> throw new IllegalArgumentException("Tipo de archivo no soportado");
            }

            System.out.println("Archivo " + tipo + " procesado y enviado al planificador.");

        } catch (IOException e) {
            throw new RuntimeException("Error leyendo archivo: " + e.getMessage());
        }
    }
}

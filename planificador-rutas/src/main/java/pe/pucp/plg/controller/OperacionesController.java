package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.pucp.plg.dto.EventDTO;
import pe.pucp.plg.service.OperationService;
import pe.pucp.plg.service.ArchivoService;

import java.util.logging.Logger;

@RestController
@RequestMapping("/api/v1/operaciones")
public class OperacionesController {

    private static final Logger logger = Logger.getLogger(OperacionesController.class.getName());

    private final OperationService operationService;
    private final ArchivoService archivoService;

    @Autowired
    public OperacionesController(OperationService operationService, ArchivoService archivoService) {
        this.operationService = operationService;
        this.archivoService = archivoService;
    }

    @PostMapping("/eventos")
    public ResponseEntity<?> registerEvent(@RequestBody EventDTO eventoDto) {
        logger.info("Registering event: ");
        try {
            operationService.registrarEventoOperacional(eventoDto);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.severe("Error registering event: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error registering event: " + e.getMessage());
        }
    }

    /*@PostMapping("/upload")
    public ResponseEntity<?> subirArchivoOperacion(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "simulationId", required = false) String simulationId) {
        String targetId = (simulationId == null || simulationId.isBlank()) ? "operational" : simulationId;
        logger.info("Subiendo archivo de operación para el contexto/simulación: " + targetId);

        if (file.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Archivo vacío");
        }
        try {
            String response = archivoService.procesarArchivoOperaciones(file, targetId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.severe("Error al procesar el archivo: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error al procesar el archivo: " + e.getMessage());
        }
    }*/
}

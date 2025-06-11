package pe.pucp.plg.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import pe.pucp.plg.service.ArchivoService;

@RestController
@RequestMapping("/api/uploads")
public class ArchivoSimulacionController {

    @Autowired
    private ArchivoService archivoService;

    /**
     * Recibe un archivo y retorna un fileId para su uso posterior en simulaciones.
     */
    @PostMapping
    public ResponseEntity<String> subirArchivo(
            @RequestParam("archivo") MultipartFile archivo) {
        try {
            String fileId = archivoService.guardarArchivoTemporal(archivo);
            return ResponseEntity.ok(fileId);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error al subir archivo: " + e.getMessage());
        }
    }
}

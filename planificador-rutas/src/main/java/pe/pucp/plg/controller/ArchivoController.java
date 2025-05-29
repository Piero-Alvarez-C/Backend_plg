package pe.pucp.plg.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.ArchivoTipo;
import pe.pucp.plg.service.ArchivoService;

@RestController
@RequestMapping("/api/archivos")
public class ArchivoController {

    private final ArchivoService archivoService;

    public ArchivoController(ArchivoService archivoService) {
        this.archivoService = archivoService;
    }

    @PostMapping("/subir")
    public ResponseEntity<String> subirArchivo(
            @RequestParam("archivo") MultipartFile archivo,
            @RequestParam("tipo") ArchivoTipo tipo
    ) {
        archivoService.procesarArchivo(archivo, tipo);
        return ResponseEntity.ok("Archivo recibido y procesado como: " + tipo.name());
    }
}

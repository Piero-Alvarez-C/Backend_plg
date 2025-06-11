package pe.pucp.plg.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.enums.ArchivoTipo;
import pe.pucp.plg.service.ArchivoService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:3000")
@RestController
@RequestMapping("/api/operations")
public class OperacionesController {

    private final ArchivoService archivoService;
    private final List<Map<String, Object>> events = new ArrayList<>();

    @Autowired
    public OperacionesController(ArchivoService archivoService) {
        this.archivoService = archivoService;
    }

    @PostMapping("/events")
    public ResponseEntity<?> registerEvent(@RequestBody Map<String, Object> event) {
        events.add(event);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/upload/{tipo}")
    public ResponseEntity<?> subirArchivoOperacion(
            @RequestParam("archivo") MultipartFile file,
            @RequestParam("tipo") ArchivoTipo tipo) {
        archivoService.procesarArchivo(file, tipo);
        return ResponseEntity.ok().build();
    }
}

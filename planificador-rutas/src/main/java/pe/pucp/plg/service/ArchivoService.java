package pe.pucp.plg.service;

import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.ArchivoTipo;

public interface ArchivoService {
    void procesarArchivo(MultipartFile archivo, ArchivoTipo tipo);
}

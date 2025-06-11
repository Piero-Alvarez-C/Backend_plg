package pe.pucp.plg.service;

import org.springframework.web.multipart.MultipartFile;
import pe.pucp.plg.dto.enums.ArchivoTipo;

public interface ArchivoService {
    void procesarArchivo(MultipartFile archivo, ArchivoTipo tipo);
    String guardarArchivoTemporal(MultipartFile archivo) throws Exception;
    byte[] obtenerArchivo(String fileId) throws Exception;
}

package pe.pucp.plg.planificador_rutas;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "pe.pucp.plg")
public class PlanificadorRutasApplication {

	public static void main(String[] args) {
		SpringApplication.run(PlanificadorRutasApplication.class, args);
	}

}

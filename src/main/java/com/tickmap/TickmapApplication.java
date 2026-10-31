package com.tickmap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@OpenAPIDefinition(info = @Info(title = "Tickmap Service", version = "1.0",
		description = "UK tick sightings: ingested from the Elanco API, cleaned and deduplicated, "
				+ "with search, aggregate stats and ingestion monitoring."))
@SpringBootApplication
public class TickmapApplication {

	public static void main(String[] args) {
		SpringApplication.run(TickmapApplication.class, args);
	}

}

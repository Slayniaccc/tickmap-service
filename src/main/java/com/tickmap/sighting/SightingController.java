package com.tickmap.sighting;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;
import com.tickmap.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/sightings")
@Tag(name = "Sightings", description = "Search and submit tick sightings")
public class SightingController {

    private static final Set<String> SORTABLE = Set.of("sightedAt", "city", "region", "species");

    public record SightingResponse(Long id, String sourceId, LocalDateTime sightedAt, String city, String region,
                                   Double latitude, Double longitude, String species, String latinName,
                                   Sighting.Source source) {

        static SightingResponse of(Sighting s) {
            return new SightingResponse(s.getId(), s.getSourceId(), s.getSightedAt(), s.getCity(), s.getRegion(),
                    s.getLatitude(), s.getLongitude(), s.getSpecies(), s.getLatinName(), s.getSource());
        }
    }

    public record CreateSightingRequest(
            @Schema(example = "2025-06-14T17:30:00") @NotNull @PastOrPresent LocalDateTime date,
            @Schema(example = "London") @NotBlank @Size(max = 100) String location,
            @Schema(example = "Marsh tick", description = "Common name; optional if latinName is given")
            @Size(max = 100) String species,
            @Schema(example = "Ixodes apronophorus") @Size(max = 150) String latinName) {
    }

    private final SightingService service;
    private final CityDirectory cities;
    private final SpeciesCatalog species;

    public SightingController(SightingService service, CityDirectory cities, SpeciesCatalog species) {
        this.service = service;
        this.cities = cities;
        this.species = species;
    }

    @Operation(summary = "Search sightings by time range and location",
            description = "All filters are optional and combined with AND. Dates accept 2024-06-01 or 2024-06-01T09:30:00; "
                    + "'to' is inclusive. Sort by sightedAt, city, region or species, e.g. sort=sightedAt,asc.")
    @GetMapping
    public PageResponse<SightingResponse> search(
            @Parameter(example = "2023-01-01") @RequestParam(required = false) String from,
            @Parameter(example = "2023-12-31") @RequestParam(required = false) String to,
            @Parameter(example = "London") @RequestParam(required = false) String city,
            @Parameter(example = "Scotland") @RequestParam(required = false) String region,
            @Parameter(example = "Marsh tick") @RequestParam(required = false) String species,
            @PageableDefault(size = 20, sort = "sightedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Cannot sort by '" + order.getProperty() + "'; use one of " + SORTABLE);
            }
        }
        SightingFilter filter = SightingFilter.parse(from, to, city, region, species, cities, this.species);
        return PageResponse.of(service.search(filter, pageable), SightingResponse::of);
    }

    @Operation(summary = "One sighting")
    @GetMapping("/{id}")
    public SightingResponse get(@PathVariable Long id) {
        return service.find(id).map(SightingResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No sighting with id " + id));
    }

    @Operation(summary = "Report a new sighting",
            description = "Validated with the same rules as ingested data. 409 if the same sighting already exists.")
    @PostMapping
    public ResponseEntity<SightingResponse> create(@Valid @RequestBody CreateSightingRequest request) {
        Sighting saved = service.create(request.date(), request.location(), request.species(), request.latinName());
        return ResponseEntity.created(URI.create("/api/sightings/" + saved.getId())).body(SightingResponse.of(saved));
    }
}

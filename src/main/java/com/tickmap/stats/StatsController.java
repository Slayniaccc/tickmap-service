package com.tickmap.stats;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;
import com.tickmap.sighting.SightingFilter;
import com.tickmap.sighting.SightingRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/stats")
@Tag(name = "Stats", description = "Aggregate insights. Every endpoint takes the same optional filters as search.")
public class StatsController {

    public record Summary(long totalSightings, LocalDateTime earliest, LocalDateTime latest,
                          int cities, int regions, int species) {}

    public record Trend(StatsRepository.Interval interval, List<StatsRepository.Bucket> buckets) {}

    private final StatsRepository stats;
    private final SightingRepository sightings;
    private final CityDirectory cities;
    private final SpeciesCatalog species;

    public StatsController(StatsRepository stats, SightingRepository sightings, CityDirectory cities, SpeciesCatalog species) {
        this.stats = stats;
        this.sightings = sightings;
        this.cities = cities;
        this.species = species;
    }

    @Operation(summary = "Totals and the date range covered")
    @GetMapping("/summary")
    public Summary summary() {
        return new Summary(sightings.count(),
                sightings.findEarliestSightedAt().orElse(null), sightings.findLatestSightedAt().orElse(null),
                stats.byCity(SightingFilter.NONE).size(), stats.byRegion(SightingFilter.NONE).size(),
                stats.bySpecies(SightingFilter.NONE).size());
    }

    @Operation(summary = "Sightings per region, most first")
    @GetMapping("/regions")
    public List<StatsRepository.RegionCount> regions(@RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String city,
                                                  @RequestParam(required = false) String region,
                                                  @RequestParam(required = false) String species) {
        return stats.byRegion(filter(from, to, city, region, species));
    }

    @Operation(summary = "Sightings per city, with coordinates for mapping")
    @GetMapping("/cities")
    public List<StatsRepository.CityCount> cities(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) String city,
                                               @RequestParam(required = false) String region,
                                               @RequestParam(required = false) String species) {
        return stats.byCity(filter(from, to, city, region, species));
    }

    @Operation(summary = "Sightings per species")
    @GetMapping("/species")
    public List<StatsRepository.SpeciesCount> species(@RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) String city,
                                                   @RequestParam(required = false) String region,
                                                   @RequestParam(required = false) String species) {
        return stats.bySpecies(filter(from, to, city, region, species));
    }

    @Operation(summary = "Sightings over time, weekly (ISO weeks, Monday start) or monthly",
            description = "Empty periods are included with count 0.")
    @GetMapping("/trends")
    public Trend trends(@Parameter(example = "month") @RequestParam(defaultValue = "month") String interval,
                        @RequestParam(required = false) String from,
                        @RequestParam(required = false) String to,
                        @RequestParam(required = false) String city,
                        @RequestParam(required = false) String region,
                        @RequestParam(required = false) String species) {
        StatsRepository.Interval i = switch (interval.trim().toLowerCase(Locale.ROOT)) {
            case "week", "weekly" -> StatsRepository.Interval.WEEK;
            case "month", "monthly" -> StatsRepository.Interval.MONTH;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "interval must be 'week' or 'month', got '" + interval + "'");
        };
        return new Trend(i, stats.trend(filter(from, to, city, region, species), i));
    }

    private SightingFilter filter(String from, String to, String city, String region, String speciesName) {
        return SightingFilter.parse(from, to, city, region, speciesName, cities, species);
    }
}

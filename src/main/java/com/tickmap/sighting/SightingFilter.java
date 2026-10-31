package com.tickmap.sighting;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.tickmap.reference.CityDirectory;
import com.tickmap.reference.SpeciesCatalog;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Shared by search and stats. from is inclusive, to is exclusive internally; a date-only
 * "to" (2024-12-31) means up to the end of that day.
 */
public record SightingFilter(LocalDateTime from, LocalDateTime toExclusive, String city, String region, String species) {

    public static final SightingFilter NONE = new SightingFilter(null, null, null, null, null);

    public static SightingFilter parse(String from, String to, String city, String region, String species,
                                       CityDirectory cities, SpeciesCatalog catalog) {
        LocalDateTime start = from == null || from.isBlank() ? null : parseBound("from", from, false);
        LocalDateTime end = to == null || to.isBlank() ? null : parseBound("to", to, true);
        if (start != null && end != null && !start.isBefore(end)) {
            throw badRequest("'from' must be before 'to'");
        }
        // Aliases resolve to the canonical name ("Newcastle upon Tyne" -> Newcastle).
        String c = blank(city) ? null : cities.find(city).map(CityDirectory.City::name).orElse(city.trim());
        String s = blank(species) ? null : catalog.findByCommonName(species).map(SpeciesCatalog.Species::commonName)
                .orElse(species.trim());
        return new SightingFilter(start, end, c, blank(region) ? null : region.trim(), s);
    }

    private static LocalDateTime parseBound(String name, String raw, boolean upper) {
        String v = raw.trim();
        try {
            if (v.length() == 10) {
                LocalDate d = LocalDate.parse(v);
                return upper ? d.plusDays(1).atStartOfDay() : d.atStartOfDay();
            }
            LocalDateTime t = LocalDateTime.parse(v);
            return upper ? t.plusSeconds(1) : t;
        } catch (DateTimeParseException e) {
            throw badRequest("'" + name + "' must be an ISO date (2024-06-01) or date-time (2024-06-01T09:30:00), got '" + raw + "'");
        }
    }

    public Specification<Sighting> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (from != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("sightedAt"), from));
            }
            if (toExclusive != null) {
                where.add(cb.lessThan(root.get("sightedAt"), toExclusive));
            }
            if (city != null) {
                where.add(equalsIgnoreCase(cb, root.get("city"), city));
            }
            if (region != null) {
                where.add(equalsIgnoreCase(cb, root.get("region"), region));
            }
            if (species != null) {
                where.add(equalsIgnoreCase(cb, root.get("species"), species));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    private static Predicate equalsIgnoreCase(CriteriaBuilder cb, Expression<String> field, String value) {
        return cb.equal(cb.lower(field), value.toLowerCase(Locale.ROOT));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

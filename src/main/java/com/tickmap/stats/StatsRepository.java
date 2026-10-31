package com.tickmap.stats;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import com.tickmap.sighting.Sighting;
import com.tickmap.sighting.SightingFilter;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;

// All aggregation is GROUP BY in the database; only grouped rows come back, never individual sightings.
@Repository
public class StatsRepository {

    public enum Interval { WEEK, MONTH }

    public record RegionCount(String region, long count) {}

    public record CityCount(String city, String region, Double latitude, Double longitude, long count) {}

    public record SpeciesCount(String species, String latinName, long count) {}

    public record Bucket(LocalDate periodStart, long count) {}

    static final int MAX_BUCKETS = 5000;

    private final EntityManager em;

    public StatsRepository(EntityManager em) {
        this.em = em;
    }

    public List<RegionCount> byRegion(SightingFilter filter) {
        return grouped(filter, r -> List.of(r.get("region")))
                .stream().map(t -> new RegionCount(t.get(0, String.class), t.get(1, Long.class))).toList();
    }

    public List<CityCount> byCity(SightingFilter filter) {
        return grouped(filter, r -> List.of(r.get("city"), r.get("region"), r.get("latitude"), r.get("longitude")))
                .stream().map(t -> new CityCount(t.get(0, String.class), t.get(1, String.class),
                        t.get(2, Double.class), t.get(3, Double.class), t.get(4, Long.class))).toList();
    }

    public List<SpeciesCount> bySpecies(SightingFilter filter) {
        return grouped(filter, r -> List.of(r.get("species"), r.get("latinName")))
                .stream().map(t -> new SpeciesCount(t.get(0, String.class), t.get(1, String.class), t.get(2, Long.class)))
                .toList();
    }

    /**
     * Counts per ISO week (starting Monday) or calendar month, with empty periods filled with 0
     * so a chart doesn't skip gaps. Groups by day in SQL, then folds days into periods here,
     * which keeps the query portable and the week boundaries correct across year ends.
     */
    public List<Bucket> trend(SightingFilter filter, Interval interval) {
        Function<LocalDate, LocalDate> periodOf = interval == Interval.WEEK
                ? d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : d -> d.withDayOfMonth(1);

        TreeMap<LocalDate, Long> counts = new TreeMap<>();
        CriteriaBuilder cb = em.getCriteriaBuilder();
        for (Tuple t : grouped(filter, r -> List.of(
                cb.function("year", Integer.class, r.get("sightedAt")),
                cb.function("month", Integer.class, r.get("sightedAt")),
                cb.function("day", Integer.class, r.get("sightedAt"))))) {
            LocalDate day = LocalDate.of(t.get(0, Integer.class), t.get(1, Integer.class), t.get(2, Integer.class));
            counts.merge(periodOf.apply(day), t.get(3, Long.class), Long::sum);
        }

        LocalDate first = filter.from() != null ? periodOf.apply(filter.from().toLocalDate())
                : counts.isEmpty() ? null : counts.firstKey();
        LocalDate last = filter.toExclusive() != null ? periodOf.apply(filter.toExclusive().minusSeconds(1).toLocalDate())
                : counts.isEmpty() ? null : counts.lastKey();
        if (first == null || last == null) {
            return List.of();
        }
        ChronoUnit unit = interval == Interval.WEEK ? ChronoUnit.WEEKS : ChronoUnit.MONTHS;
        if (unit.between(first, last) >= MAX_BUCKETS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Range too large for " + interval.name().toLowerCase() + "ly buckets; narrow it or use a larger interval");
        }
        List<Bucket> buckets = new ArrayList<>();
        for (LocalDate p = first; !p.isAfter(last); p = p.plus(1, unit)) {
            buckets.add(new Bucket(p, counts.getOrDefault(p, 0L)));
        }
        return buckets;
    }

    private List<Tuple> grouped(SightingFilter filter, Function<Root<Sighting>, List<? extends Expression<?>>> keysOf) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> q = cb.createTupleQuery();
        Root<Sighting> root = q.from(Sighting.class);
        List<? extends Expression<?>> keys = keysOf.apply(root);
        Expression<Long> count = cb.count(root);

        List<Selection<?>> select = new ArrayList<>(keys);
        select.add(count);
        q.multiselect(select)
                .where(filter.toSpecification().toPredicate(root, q, cb))
                .groupBy(new ArrayList<>(keys))
                .orderBy(cb.desc(count), cb.asc(keys.getFirst()));
        return em.createQuery(q).getResultList();
    }
}

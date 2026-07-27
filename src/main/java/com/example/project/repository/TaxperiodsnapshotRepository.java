package com.example.project.repository;

import com.example.project.entity.Taxperiodsnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Tax period snapshots ("kỳ thuế đã chốt"). The entity has no relations, so there is nothing to
 * fetch-join — the queries here exist only to fix an ordering, since the period <em>chain</em> is
 * what every caller actually needs: a period's revenue group comes from the previous period's
 * {@code nextPeriodTaxType} and its {@code vatCarryforwardIn} from that period's
 * {@code vatCarryforwardOut}.
 */
public interface TaxperiodsnapshotRepository extends JpaRepository<Taxperiodsnapshot, Integer> {

    /** Newest period first — the order the list screen shows. */
    @Query("""
           select t
           from Taxperiodsnapshot t
           order by t.startDate desc, t.id desc
           """)
    List<Taxperiodsnapshot> findAllNewestFirst();

    /** Oldest period first — the order the chain must be walked in. */
    @Query("""
           select t
           from Taxperiodsnapshot t
           order by t.startDate asc, t.id asc
           """)
    List<Taxperiodsnapshot> findAllOldestFirst();

    /**
     * The most recently closed period, i.e. the tail of the chain. Its {@code nextPeriodTaxType}
     * decides the group of the period being closed next, and its {@code vatCarryforwardOut} becomes
     * that period's {@code vatCarryforwardIn}.
     *
     * <p>A derived query rather than a {@code default} method walking {@link #findAllNewestFirst()}:
     * a {@code default} method is mocked away like any other when the repository is stubbed in a
     * unit test, so the "latest" rule would silently become whatever the test set it to.</p>
     */
    Optional<Taxperiodsnapshot> findFirstByOrderByStartDateDescIdDesc();

    /** Guards against closing the same quarter twice — {@code periodLabel} is the business key. */
    List<Taxperiodsnapshot> findByPeriodLabelOrderByIdAsc(String periodLabel);

    boolean existsByPeriodLabel(String periodLabel);
}

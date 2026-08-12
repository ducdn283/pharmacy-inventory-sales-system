package com.example.project.repository;

import com.example.project.entity.Shiftreport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ShiftreportRepository extends JpaRepository<Shiftreport, Integer> {

    @Query("""
           select s
           from Shiftreport s
           left join fetch s.cashierID
           order by s.startTime desc
           """)
    List<Shiftreport> findAllWithRelations();

    @Query("""
           select s
           from Shiftreport s
           left join fetch s.cashierID
           where s.id = :id
           """)
    Optional<Shiftreport> findByIdWithRelations(@Param("id") Integer id);

    /** Open (Nháp) shift of an account, if any — used by ensureOpenShiftFor / logout guard. */
    Optional<Shiftreport> findFirstByCashierID_IdAndStatusOrderByStartTimeDesc(Integer cashierId, String status);

    /**
     * OLDEST open (Nháp) shift of an account — used to find one left unclosed from a previous day.
     * Deliberately not the newest: an account should only ever have one draft, but if an older one
     * did survive alongside a newer one, the stale one is exactly what must be closed first and
     * looking at the newest would miss it.
     */
    Optional<Shiftreport> findFirstByCashierID_IdAndStatusOrderByStartTimeAsc(Integer cashierId, String status);

    /**
     * Ca gần nhất của một người, bất kể trạng thái — mốc chặn ca mở tay bị nhập giờ đè lên ca trước
     * (xem {@code ShiftreportService.createManualShift}).
     */
    Optional<Shiftreport> findFirstByCashierID_IdOrderByStartTimeDesc(Integer cashierId);
}

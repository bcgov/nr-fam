package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamPrivilegeChangeAuditRepository
    extends JpaRepository<FamPrivilegeChangeAudit, Long> {

  /**
   * Audit history for one user within one application, most recent change first.
   *
   * <p>Ordered by {@code change_date}, not {@code create_date}: backfilled rows
   * carry a historical change date that differs from when the row was written.
   */
  @Query("""
      select a from FamPrivilegeChangeAudit a
      join fetch a.privilegeChangeType
      where a.changeTargetUser.userId = :userId
        and a.application.applicationId = :applicationId
      order by a.changeDate desc
      """)
  List<FamPrivilegeChangeAudit> findHistory(
      @Param("userId") Long userId, @Param("applicationId") Long applicationId);
}

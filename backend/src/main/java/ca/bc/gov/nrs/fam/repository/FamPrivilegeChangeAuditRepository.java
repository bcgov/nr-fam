package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamPrivilegeChangeAuditRepository
    extends JpaRepository<FamPrivilegeChangeAudit, Long> {

  /**
   * Audit history for one user within one CSS integration environment, most
   * recent change first.
   *
   * <p>Keyed on the target user's GUID rather than a user id: the audit keeps no
   * foreign key into fam_user, so that history survives the user record.
   *
   * <p>Ordered by {@code change_date}, not {@code create_date}: backfilled rows
   * carry a historical change date that differs from when the row was written.
   */
  @Query("""
      select a from FamPrivilegeChangeAudit a
      join fetch a.privilegeChangeType
      where upper(a.targetUserGuid) = upper(:targetUserGuid)
        and a.cssIntegrationId = :cssIntegrationId
        and a.cssEnvironment = :cssEnvironment
      order by a.changeDate desc
      """)
  List<FamPrivilegeChangeAudit> findHistory(
      @Param("targetUserGuid") String targetUserGuid,
      @Param("cssIntegrationId") Integer cssIntegrationId,
      @Param("cssEnvironment") String cssEnvironment);
}

package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FamPrivilegeChangeAuditRepository
    extends JpaRepository<FamPrivilegeChangeAudit, UUID> {

  /**
   * Audit history for one user within one CSS integration environment, most
   * recent change first.
   *
   * <p>Keyed on the target user's GUID rather than a user id: the audit keeps no
   * reference to any user record, so history survives independently of one.
   *
   * <p>Ordered by {@code change_date}, not {@code create_date}: backfilled rows
   * carry a historical change date that differs from when the row was written.
   */
  @Query("""
      select a from FamPrivilegeChangeAudit a
      join fetch a.privilegeChangeType
      where upper(a.targetUser) = upper(:targetUser)
        and a.cssIntegrationId = :cssIntegrationId
        and a.cssEnvironment = :cssEnvironment
      order by a.changeDate desc
      """)
  List<FamPrivilegeChangeAudit> findHistory(
      @Param("targetUser") String targetUser,
      @Param("cssIntegrationId") Integer cssIntegrationId,
      @Param("cssEnvironment") String cssEnvironment);

  /**
   * Every change made in one application, newest first, as the columns the user
   * list needs.
   *
   * <p>A projection rather than the entities: the caller wants one row per person
   * and takes the newest of each, so loading whole audit records - two JSONB
   * documents apiece - to discard most of them would be the expensive way to ask.
   *
   * <p>Not grouped in SQL either. The newest row's snapshot of the person is what
   * the list shows, and an aggregate cannot pick "the details belonging to the
   * max date" - {@code max()} over a JSON string sorts it lexicographically and
   * returns somebody else's name.
   */
  @Query("""
      select a.targetUser, a.changeDate, a.changeTargetUserDetails
      from FamPrivilegeChangeAudit a
      where a.cssIntegrationId = :cssIntegrationId
        and a.cssEnvironment = :cssEnvironment
        and a.targetUser is not null
      order by a.changeDate desc
      """)
  List<Object[]> findTargetUsersForApplication(
      @Param("cssIntegrationId") Integer cssIntegrationId,
      @Param("cssEnvironment") String cssEnvironment);

  /**
   * The newest snapshot the trail holds of each of these people, from anywhere.
   *
   * <p>Not scoped to one application on purpose. FAM snapshots who somebody was
   * every time it records a change about them, so a person who arrives unnamed
   * in one application's trail - which is how legacy rows arrive, legacy having
   * recorded nothing about the target of a change - is very often named in
   * another's, where FAM granted them something itself.
   *
   * <p>Better than asking the directory, for the same reason the trail snapshots
   * at all: this is what FAM recorded at the time, not what is true today. It
   * also still answers for somebody who has since left, where the directory
   * would not.
   *
   * <p>Matched on GUID rather than username. A username is unique within a
   * directory but the same string could exist in both, and this is identity.
   *
   * @return rows of {@code [user_guid, change_target_user_details]}, newest per
   *     person
   */
  @Query(value = """
      SELECT DISTINCT ON (change_target_user_details->>'user_guid')
             change_target_user_details->>'user_guid',
             change_target_user_details
      FROM app_fam.fam_privilege_change_audit
      WHERE change_target_user_details->>'user_guid' IN (:userGuids)
        AND jsonb_exists(change_target_user_details, 'email')
      ORDER BY change_target_user_details->>'user_guid', change_date DESC
      """, nativeQuery = true)
  List<Object[]> findKnownIdentities(@Param("userGuids") java.util.Collection<String> userGuids);

  /**
   * Fill in the identity of somebody the trail could not name.
   *
   * <p><b>This writes to an audit table, which deserves a word.</b> It does not
   * alter a recorded fact: every field describing what happened - who did it,
   * to whom, which role, when - is untouched. It fills a hole where the record
   * never held a name at all, which is the state rows migrated from the legacy
   * system arrive in, because legacy stored identity details for the performer
   * of a change and nothing for its target.
   *
   * <p><b>Only where there is a hole.</b> The {@code jsonb_exists} guard means a
   * row that already carries an email is never rewritten, so a snapshot taken at
   * the time of the change always survives a later lookup that disagrees with
   * it. Re-running is therefore a no-op.
   *
   * <p>Every row for that person is filled, not only the one being displayed:
   * the cost is the same single statement, and it means the answer does not
   * depend on which row happens to be newest next time.
   *
   * <p>{@code update_user} records {@code system} rather than a person, because
   * no person asked for this - it is FAM caching what it had to look up. That
   * keeps {@code create_user}, which names who made the change, honest.
   *
   * @return how many rows were filled
   */
  @Modifying
  @Query(value = """
      UPDATE app_fam.fam_privilege_change_audit
      SET change_target_user_details = CAST(:details AS jsonb),
          update_user = 'system',
          update_date = LOCALTIMESTAMP
      WHERE target_user = :targetUser
        AND NOT jsonb_exists(
              COALESCE(change_target_user_details, '{}'::jsonb), 'email')
      """, nativeQuery = true)
  int fillMissingTargetDetails(
      @Param("targetUser") String targetUser,
      @Param("details") String details);
}

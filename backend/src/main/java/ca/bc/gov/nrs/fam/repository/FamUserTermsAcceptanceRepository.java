package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamUserTermsAcceptance;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamUserTermsAcceptanceRepository
    extends JpaRepository<FamUserTermsAcceptance, UUID> {

  /**
   * Whether this person has accepted this version.
   *
   * <p>Exact match on {@code accepted_user}: {@code AuditUser.of} uppercases the
   * GUID on the way in, so both sides are already in one form.
   */
  boolean existsByAcceptedUserAndTermsVersion(String acceptedUser, String termsVersion);
}

package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One delegated administrator's acceptance of one version of the Terms of Use.
 *
 * <p>Append-only. A change to the terms is a new version and so a new row; the
 * acceptance of the old version stays as the record of what was agreed to then.
 *
 * <p>No relationship to a user, for the same reason the audit trail has none -
 * FAM keeps no user table. {@link #acceptedUser} names the person in the
 * {@code <TYPE>\<GUID>} form of {@link ca.bc.gov.nrs.fam.security.AuditUser}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_user_terms_acceptance")
public class FamUserTermsAcceptance extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "user_terms_acceptance_id")
  private UUID userTermsAcceptanceId;

  /** Who accepted, as {@code BCEID_BUS\<GUID>}. */
  @Column(name = "accepted_user", nullable = false, length = 100)
  private String acceptedUser;

  /** Username as at acceptance. A snapshot for readers, never matched on. */
  @Column(name = "user_name", length = 100)
  private String userName;

  /** The organisation accepted on behalf of, as at acceptance. */
  @Column(name = "business_guid", length = 32)
  private String businessGuid;

  @Column(name = "terms_version", nullable = false, length = 30)
  private String termsVersion;
}

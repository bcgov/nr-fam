package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Records that a user accepted a given version of the terms and conditions.
 *
 * <p>One row per user per version, so acceptance history is preserved when the
 * terms are revised. The version currently in force is
 * {@link ca.bc.gov.nrs.fam.constants.FamConstants#CURRENT_TERMS_AND_CONDITIONS_VERSION},
 * which must be kept in sync with the frontend's TermsAndConditions component.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_user_terms_conditions",
    uniqueConstraints = @UniqueConstraint(name = "fam_tc_user_version_uk",
        columnNames = {"user_id", "version"}))
public class FamUserTermsConditions extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_terms_conditions_id")
  private Long userTermsConditionsId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private FamUser user;

  @Column(name = "version", nullable = false, length = 30)
  private String version;
}

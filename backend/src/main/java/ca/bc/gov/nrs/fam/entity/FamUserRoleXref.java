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
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An end-user's assignment to a role. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_user_role_xref",
    uniqueConstraints = @UniqueConstraint(name = "fam_usr_rle_usr_id_rle_id_uk",
        columnNames = {"user_id", "role_id"}))
public class FamUserRoleXref extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_role_xref_id")
  private Long userRoleXrefId;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private FamUser user;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private FamRole role;

  /**
   * When the assignment lapses. NULL means it never expires (V74).
   *
   * <p>Expiry is not enforced by a database constraint or a scheduled job; it is
   * evaluated on read.
   */
  @Column(name = "expiry_date")
  private OffsetDateTime expiryDate;

  /** True when this assignment has an expiry date that has already passed. */
  public boolean isExpired() {
    return expiryDate != null && expiryDate.isBefore(OffsetDateTime.now());
  }
}

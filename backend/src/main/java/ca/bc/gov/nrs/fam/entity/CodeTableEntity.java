package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Shared shape of the FAM code tables ({@code fam_user_type_code},
 * {@code fam_role_type}, {@code fam_app_environment},
 * {@code fam_privilege_change_type}).
 *
 * <p>Code tables carry no {@code create_user}/{@code create_date}; rows are
 * seeded by migrations. {@code expiry_date} marks a code as retired - it is
 * never used to filter reads today, but it is part of the schema.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class CodeTableEntity {

  @Column(name = "effective_date", nullable = false)
  private OffsetDateTime effectiveDate;

  @Column(name = "expiry_date")
  private OffsetDateTime expiryDate;

  @UpdateTimestamp
  @Column(name = "update_date", nullable = false)
  private OffsetDateTime updateDate;
}

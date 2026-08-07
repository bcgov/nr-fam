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
 * A delegated administrator: a user permitted to manage one specific role of an
 * application, without being a full application admin.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_access_control_privilege",
    uniqueConstraints = @UniqueConstraint(name = "fam_access_control_usr_rle_uk",
        columnNames = {"user_id", "role_id"}))
public class FamAccessControlPrivilege extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "access_control_privilege_id")
  private Long accessControlPrivilegeId;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private FamUser user;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "role_id", nullable = false)
  private FamRole role;

  @Override
  public String toString() {
    return "FamAccessControlPrivilege(user_id=%s, role_id=%s)".formatted(
        user != null ? user.getUserId() : null, role != null ? role.getRoleId() : null);
  }
}

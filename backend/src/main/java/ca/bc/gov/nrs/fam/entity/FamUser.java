package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A person or system that can authenticate and then interact with an application. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_user",
    uniqueConstraints = @UniqueConstraint(name = "fam_usr_uk",
        columnNames = {"user_type_code", "user_guid"}))
public class FamUser extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "user_id")
  private Long userId;

  /** Writable side of the code column; {@link #userType} is the read-only join. */
  @Column(name = "user_type_code", nullable = false, length = 2)
  private String userTypeCode;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "user_type_code", referencedColumnName = "user_type_code",
      insertable = false, updatable = false)
  private FamUserType userType;

  @Column(name = "user_name", nullable = false, length = 100)
  private String userName;

  /**
   * The identity provider's GUID. Together with {@code user_type_code} this is
   * the natural key (V50); {@code user_name} is not unique across providers.
   */
  @Column(name = "user_guid", length = 32)
  private String userGuid;

  /** Set only for Business BCeID users; identifies their organisation. */
  @Column(name = "business_guid", length = 32)
  private String businessGuid;

  /**
   * The OIDC subject of this user.
   *
   * <p>Named for AWS Cognito, which FAM no longer uses. It now holds the BC Gov
   * SSO (Keycloak) subject claim. The column was called {@code cognito_user_id}
   * until the baseline migration, which renamed it to match what it holds -
   * there was no seed data left to preserve by then.
   */
  @Column(name = "oidc_user_id", length = 100)
  private String oidcUserId;

  @Column(name = "first_name", length = 50)
  private String firstName;

  @Column(name = "last_name", length = 50)
  private String lastName;

  @Column(name = "email", length = 250)
  private String email;




  /**
   * Port of the {@code full_name} hybrid property. A user with no first name
   * yields just the last name, rather than "null Smith".
   */
  public String getFullName() {
    return firstName != null ? firstName + " " + lastName : lastName;
  }

  @Override
  public String toString() {
    return "FamUser(%d, %s, %s)".formatted(userId, userName, userTypeCode);
  }
}

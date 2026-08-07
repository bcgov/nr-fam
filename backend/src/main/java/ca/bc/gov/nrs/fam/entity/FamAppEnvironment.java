package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** DEV, TEST or PROD - which environment an application row represents. */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_app_environment")
public class FamAppEnvironment extends CodeTableEntity {

  @Id
  @Column(name = "app_environment", length = 4, nullable = false)
  private String appEnvironment;

  @Column(name = "description", length = 100)
  private String description;
}

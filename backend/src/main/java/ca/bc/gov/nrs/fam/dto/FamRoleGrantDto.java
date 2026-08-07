package ca.bc.gov.nrs.fam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ca.bc.gov.nrs.fam.constants.RoleType;
import java.util.List;

/**
 * A role a user may grant to others.
 *
 * <p>{@code id} may be an abstract or a concrete role. For an abstract role,
 * {@code forestClients} lists the clients the delegated admin holds authority
 * for - FAM materialises one child role per client, and this collapses them back
 * into the parent for display.
 */

public record FamRoleGrantDto(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RoleType typeCode,
    List<FamForestClientBase> forestClients) {

  /** Same role, with the client list attached. */
  public FamRoleGrantDto withForestClients(List<FamForestClientBase> clients) {
    return new FamRoleGrantDto(id, name, displayName, description, typeCode, clients);
  }
}

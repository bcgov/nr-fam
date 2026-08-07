package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.dto.AdminUserAccessResponse;
import ca.bc.gov.nrs.fam.security.Requester;
import ca.bc.gov.nrs.fam.service.AdminUserAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of {@code router_admin_user_accesses.py}. */
@RestController
@RequestMapping("/admin-user-accesses")
@Tag(name = "Admin User Accesses")
@RequiredArgsConstructor
public class AdminUserAccessController {

  private final AdminUserAccessService adminUserAccessService;

  /**
   * What the signed-in administrator may grant.
   *
   * <p>Deliberately has no authorization guard: it reports the caller's own
   * access, so a user with none legitimately receives an empty list. Guarding it
   * would make the frontend unable to tell "no access" from "not allowed to ask".
   */
  @GetMapping
  @Operation(operationId = "admin_user_access_privilege", summary = "Admin user access privilege",
      description = "Access privilege for the logged-on admin user: which "
          + "applications and scoped roles the user can grant.")
  public AdminUserAccessResponse getAdminUserAccess(Requester requester) {
    return adminUserAccessService.getAccessGrants(requester.userId());
  }
}

package ca.bc.gov.nrs.fam.controller;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.dto.FamUserUpdateResponse;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.service.UserInfoRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk refresh of stored user details from IDIM.
 *
 * <p>Port of {@code router_user.py}. Called by a scheduled job, not by a person,
 * so it is guarded by a shared secret in {@code X-API-Key} rather than a bearer
 * token - there is no signed-in user to resolve.
 */
@Slf4j
@RestController
@RequestMapping("/users")
@Tag(name = "FAM User")
@RequiredArgsConstructor
public class UserInfoRefreshController {

  private final UserInfoRefreshService userInfoRefreshService;
  private final FamProperties famProperties;

  /**
   * Refresh every IDIR and Business BCeID user against IDIM.
   *
   * @param page 1-indexed; only meaningful with {@code usePagination}
   */
  @PutMapping("/users-information")
  @Operation(operationId = "update_user_information_from_idim_source",
      summary = "Refresh stored user details from IDIM",
      description = "Guarded by the X-API-Key shared secret, not a bearer token.",
      security = @SecurityRequirement(name = "apiKey"))
  public FamUserUpdateResponse refreshUserInformation(
      @RequestHeader(name = "X-API-Key", required = false) String apiKey,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "100") int perPage,
      @RequestParam(defaultValue = "false") boolean usePagination) {

    verifyApiKey(apiKey);

    log.debug("Refreshing user information from IDIM (paginated={}, page={})",
        usePagination, page);

    return userInfoRefreshService.refreshFromIdim(usePagination, page, perPage);
  }

  /**
   * Port of {@code router_guards.verify_api_key_for_update_user_info}.
   *
   * <p>Compared in constant time: a plain equals leaks the shared secret one
   * character at a time to anyone who can measure the response.
   *
   * <p>An unconfigured key rejects every call rather than allowing them - the
   * endpoint rewrites user records in bulk, so failing closed is the only safe
   * default.
   */
  private void verifyApiKey(String apiKey) {
    String expected = famProperties.updateUserInfo().apiKey();

    if (expected == null || expected.isBlank()) {
      log.warn("Rejecting user-information refresh: no API key is configured.");
      throw new FamHttpException(HttpStatus.UNAUTHORIZED,
          ErrorCode.INVALID_OPERATION, "Request needs api key.");
    }

    if (apiKey == null || !MessageDigest.isEqual(
        apiKey.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
      throw new FamHttpException(HttpStatus.UNAUTHORIZED,
          ErrorCode.INVALID_OPERATION, "Request needs api key.");
    }
  }
}

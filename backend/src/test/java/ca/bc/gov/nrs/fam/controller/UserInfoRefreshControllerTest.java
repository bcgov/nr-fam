package ca.bc.gov.nrs.fam.controller;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.FamUserUpdateResponse;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.service.UserInfoRefreshService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * The shared-secret guard on the bulk refresh.
 *
 * <p>This endpoint rewrites user records in bulk and has no signed-in user, so
 * the key check is the only thing standing in front of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UserInfoRefreshController API key guard")
class UserInfoRefreshControllerTest {

  @Mock private UserInfoRefreshService refreshService;

  private UserInfoRefreshController controller(String configuredKey) {
    FamProperties properties = new FamProperties("dev", null, null,
        new FamProperties.UpdateUserInfo(configuredKey, "CMENG"));
    when(refreshService.refreshFromIdim(anyBoolean(), anyInt(), anyInt()))
        .thenReturn(new FamUserUpdateResponse(0, 1, 0, OffsetDateTime.now(), "0s",
            List.of(), List.of(), List.of(), List.of()));
    return new UserInfoRefreshController(refreshService, properties);
  }

  @Test
  @DisplayName("accepts the configured key")
  void acceptsCorrectKey() {
    assertThatCode(() -> controller("secret")
        .refreshUserInformation("secret", 1, 100, false))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong", "secre", "secret ", "SECRET"})
  @DisplayName("rejects a key that is not exactly right")
  void rejectsWrongKey(String supplied) {
    assertThatThrownBy(() -> controller("secret")
        .refreshUserInformation(supplied, 1, 100, false))
        .isInstanceOf(FamHttpException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @DisplayName("rejects a missing key")
  void rejectsMissingKey(String supplied) {
    assertThatThrownBy(() -> controller("secret")
        .refreshUserInformation(supplied, 1, 100, false))
        .isInstanceOf(FamHttpException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  @DisplayName("fails closed when no key is configured, rather than allowing every call")
  void failsClosedWhenUnconfigured(String configured) {
    // An unconfigured secret must not mean "no check".
    assertThatThrownBy(() -> controller(configured)
        .refreshUserInformation("anything", 1, 100, false))
        .isInstanceOf(FamHttpException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    verify(refreshService, never()).refreshFromIdim(anyBoolean(), anyInt(), anyInt());
  }
}

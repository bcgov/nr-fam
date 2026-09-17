package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.FamConstants;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.entity.FamUserTermsAcceptance;
import ca.bc.gov.nrs.fam.exception.FamHttpException;
import ca.bc.gov.nrs.fam.repository.FamUserTermsAcceptanceRepository;
import ca.bc.gov.nrs.fam.security.Requester;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Who has to accept the FAM Terms of Use, and recording that they did.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TermsAcceptanceService")
class TermsAcceptanceServiceTest {

  private static final String GUID = "a1b2c3d4e5f60718293a4b5c6d7e8f90";
  private static final String ACCEPTED_USER = "BCEID_BUS\\A1B2C3D4E5F60718293A4B5C6D7E8F90";
  private static final String VERSION = FamConstants.CURRENT_TERMS_AND_CONDITIONS_VERSION;

  @Mock private FamUserTermsAcceptanceRepository repository;
  @InjectMocks private TermsAcceptanceService service;

  private static Requester bceidDelegatedAdmin() {
    return Requester.builder()
        .userName("JSMITH")
        .userType(UserType.BCEID)
        .userGuid(GUID)
        .businessGuid("B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0")
        .accessRoles(List.of("DELEGATED_ADMIN_22264_DEV__FREP_EDITOR"))
        .isDelegatedAdmin(true)
        .build();
  }

  @Test
  @DisplayName("any DELEGATED_ADMIN_ role makes the holder a delegated administrator")
  void delegatedAdminIsReadFromRoles() {
    assertThat(TermsAcceptanceService.holdsDelegatedAdminRole(
        List.of("DELEGATED_ADMIN_22264_DEV"))).isTrue();
    assertThat(TermsAcceptanceService.holdsDelegatedAdminRole(
        List.of("delegated_admin_22264_dev__FREP_EDITOR"))).isTrue();
    assertThat(TermsAcceptanceService.holdsDelegatedAdminRole(
        List.of("FAM_ADMIN", "APP_ADMIN_22264_DEV", "DEVOPS_ADMIN_22264_DEV"))).isFalse();
    assertThat(TermsAcceptanceService.holdsDelegatedAdminRole(null)).isFalse();
  }

  @Test
  @DisplayName("an IDIR delegated administrator is never asked, and costs no query")
  void idirIsNeverAsked() {
    assertThat(service.requiresAcceptance(UserType.IDIR, GUID, true)).isFalse();
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("a BCeID user who is not a delegated administrator is never asked")
  void nonDelegatedBceidIsNeverAsked() {
    assertThat(service.requiresAcceptance(UserType.BCEID, GUID, false)).isFalse();
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("a BCeID delegated administrator is asked until the current version is accepted")
  void bceidDelegatedAdminIsAskedUntilAccepted() {
    when(repository.existsByAcceptedUserAndTermsVersion(ACCEPTED_USER, VERSION))
        .thenReturn(false, true);

    assertThat(service.requiresAcceptance(UserType.BCEID, GUID, true)).isTrue();
    assertThat(service.requiresAcceptance(UserType.BCEID, GUID, true)).isFalse();
  }

  @Test
  @DisplayName("accepting records the person, organisation and current version")
  void acceptRecordsAcceptance() {
    when(repository.existsByAcceptedUserAndTermsVersion(ACCEPTED_USER, VERSION)).thenReturn(false);

    service.accept(bceidDelegatedAdmin());

    ArgumentCaptor<FamUserTermsAcceptance> saved =
        ArgumentCaptor.forClass(FamUserTermsAcceptance.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getAcceptedUser()).isEqualTo(ACCEPTED_USER);
    assertThat(saved.getValue().getCreateUser()).isEqualTo(ACCEPTED_USER);
    assertThat(saved.getValue().getUserName()).isEqualTo("JSMITH");
    assertThat(saved.getValue().getBusinessGuid()).isEqualTo("B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0B0");
    assertThat(saved.getValue().getTermsVersion()).isEqualTo(VERSION);
  }

  @Test
  @DisplayName("accepting twice is not an error and writes nothing the second time")
  void acceptIsIdempotent() {
    when(repository.existsByAcceptedUserAndTermsVersion(ACCEPTED_USER, VERSION)).thenReturn(true);

    assertThatCode(() -> service.accept(bceidDelegatedAdmin())).doesNotThrowAnyException();
    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("a concurrent duplicate caught by the unique constraint counts as accepted")
  void concurrentDuplicateIsAccepted() {
    when(repository.existsByAcceptedUserAndTermsVersion(anyString(), anyString())).thenReturn(false);
    when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

    assertThatCode(() -> service.accept(bceidDelegatedAdmin())).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("anybody the terms do not apply to is refused rather than recorded")
  void refusesAnybodyElse() {
    Requester idir = bceidDelegatedAdmin().toBuilder().userType(UserType.IDIR).build();
    Requester notDelegated = bceidDelegatedAdmin().toBuilder().isDelegatedAdmin(false).build();

    for (Requester requester : List.of(idir, notDelegated)) {
      assertThatThrownBy(() -> service.accept(requester))
          .isInstanceOf(FamHttpException.class)
          .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN)
          .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_OPERATION);
    }
    verifyNoInteractions(repository);
  }
}

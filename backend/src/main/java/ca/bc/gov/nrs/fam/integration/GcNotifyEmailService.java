package ca.bc.gov.nrs.fam.integration;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.GcNotifyGrantAccessEmailParams;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Sends the "access granted" notification through GC Notify.
 *
 * <p>Port of {@code integration/gc_notify.py}. Lower environments use a
 * team-and-safelist API key, production uses a live key; the key type is set by
 * configuration, not by code.
 *
 * <p>GC Notify templates cannot do conditional rendering or interpolate a
 * variable inside conditional text, so all the branching is done here and the
 * template receives finished strings. That is why the personalisation map
 * contains prose rather than flags.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GcNotifyEmailService {

  private static final String UPSTREAM = "gc-notify";

  /**
   * Shown only to BCeID delegated admins, who must accept FAM's terms of use. The
   * wording is duplicated in the frontend's TermsAndConditions component; the two
   * must be revised together.
   */
  private static final String TERMS_CONDITIONS_COMPLY_TEXT =
      "You must comply with the FAM terms and conditions of use.";

  private final FamProperties famProperties;
  private final RestClientFactory restClientFactory;
  private final UpstreamErrorTranslator errorTranslator;
  private final ObjectMapper objectMapper;

  private RestClient client;
  private String templateId;
  private String delegatedAdminTemplateId;

  @PostConstruct
  void initClient() {
    FamProperties.Integration.GcNotify config = famProperties.integration().gcNotify();
    this.templateId = config.grantAccessTemplateId();
    this.delegatedAdminTemplateId = config.grantDelegatedAdminTemplateId();
    this.client = restClientFactory.create(
        config.baseUrl(),
        config.timeouts().connect(),
        config.timeouts().read(),
        headers -> {
          headers.setContentType(MediaType.APPLICATION_JSON);
          // GC Notify's own scheme, not Bearer.
          headers.set(HttpHeaders.AUTHORIZATION, "ApiKey-v1 " + config.apiKey());
        });
  }

  public Map<String, Object> sendUserAccessGrantedEmail(GcNotifyGrantAccessEmailParams params) {
    Map<String, Object> personalisation = new LinkedHashMap<>();
    personalisation.put("user_name", params.userName());
    personalisation.put("first_name", params.firstName());
    personalisation.put("last_name", params.lastName());
    personalisation.put("application_name", params.applicationDescription());
    personalisation.put("application_role_granted_text", grantedText(params));
    personalisation.put("organization_list_text", organizationListText(params));
    personalisation.put("contact_message", contactMessage(params));

    Map<String, Object> body = Map.of(
        "email_address", params.sendToEmail(),
        "template_id", templateId,
        "personalisation", personalisation);

    log.debug("Sending user access granted email to {}", params.sendToEmail());
    return send(body);
  }

  private Map<String, Object> send(Map<String, Object> body) {
    try {
      ResponseEntity<byte[]> response = client.post()
          .uri("/v2/notifications/email")
          .body(body)
          .retrieve()
          .toEntity(byte[].class);

      if (response.getStatusCode().isError()) {
        throw errorTranslator.httpError(UPSTREAM, response.getStatusCode(), response.getBody(),
            HttpStatus.valueOf(response.getStatusCode().value()).getReasonPhrase());
      }
      return parseBody(response.getBody());

    } catch (ResourceAccessException e) {
      throw errorTranslator.connectivityFailure(UPSTREAM, e);
    }
  }

  /**
   * The lead sentence, which differs depending on whether the role is scoped to
   * organisations - the scoped variant ends with a colon introducing the list.
   */
  private static String grantedText(GcNotifyGrantAccessEmailParams params) {
    if (params.organizationList() == null) {
      return "You have been granted access to **%s** with a **%s** role."
          .formatted(params.applicationDescription(), params.roleDisplayName());
    }
    return "You have been granted access to **%s** with a **%s** role for the following "
        .formatted(params.applicationDescription(), params.roleDisplayName()) + "organizations:";
  }

  /**
   * A markdown bullet per organisation.
   *
   * <p>Null organisation list means an unscoped role and yields an empty string;
   * the template always renders this variable, so it can never be null.
   */
  private static String organizationListText(GcNotifyGrantAccessEmailParams params) {
    List<FamForestClientDto> organizations = params.organizationList();
    if (organizations == null) {
      return "";
    }
    return organizations.stream()
        .map(o -> "* **%s** (Client number: %s)".formatted(
            o.clientName(), o.forestClientNumber()))
        .collect(Collectors.joining("\n"));
  }

  private static String contactMessage(GcNotifyGrantAccessEmailParams params) {
    return params.applicationTeamContactEmail() != null
        ? "Please contact your administrator %s if you have any issues accessing the application."
            .formatted(params.applicationTeamContactEmail())
        : "Please contact your administrator if you have any issues accessing the application.";
  }

  /**
   * Notify a user that they have been made a delegated administrator.
   *
   * <p>A different GC Notify template from the end-user grant: it explains the
   * administrative capability rather than the access itself.
   *
   * @param includeTermsAndConditions only BCeID delegated admins must accept the
   *     terms, so the template variable is left empty for everyone else. It is
   *     always sent, because GC Notify cannot render a missing variable.
   */
  public Map<String, Object> sendDelegatedAdminGrantedEmail(
      GcNotifyGrantAccessEmailParams params, boolean includeTermsAndConditions) {

    Map<String, Object> personalisation = new LinkedHashMap<>();
    personalisation.put("user_name", params.userName());
    personalisation.put("first_name", params.firstName());
    personalisation.put("last_name", params.lastName());
    personalisation.put("application_name", params.applicationDescription());
    personalisation.put("application_role_granted_text", grantedText(params));
    personalisation.put("organization_list_text", organizationListText(params));
    personalisation.put("contact_message", contactMessage(params));
    personalisation.put("terms_conditions_comply_text",
        includeTermsAndConditions ? TERMS_CONDITIONS_COMPLY_TEXT : "");

    return send(Map.of(
        "email_address", params.sendToEmail(),
        "template_id", delegatedAdminTemplateId,
        "personalisation", personalisation));
  }

  private Map<String, Object> parseBody(byte[] body) {
    if (body == null || body.length == 0) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
    } catch (IOException e) {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, null,
          "Unreadable response from GC Notify: " + new String(body, StandardCharsets.UTF_8),
          UPSTREAM, e);
    }
  }
}

package ca.bc.gov.nrs.fam.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.bc.gov.nrs.fam.configuration.FamProperties;
import ca.bc.gov.nrs.fam.dto.FamForestClientDto;
import ca.bc.gov.nrs.fam.dto.GcNotifyGrantAccessEmailParams;
import ca.bc.gov.nrs.fam.exception.UpstreamException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

@DisplayName("GcNotifyEmailService (port of gc_notify.py)")
class GcNotifyEmailServiceTest {

  private static final String TEMPLATE_ID = "0806a36e-b33d-4e43-a401-b1eb92777116";
  private static final String DELEGATED_ADMIN_TEMPLATE_ID = "4f36da24-7507-4813-8285-d66a254c1f88";

  private MockWebServer server;
  private GcNotifyEmailService service;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    server = new MockWebServer();
    server.start();

    FamProperties.Integration.GcNotify config = new FamProperties.Integration.GcNotify(
        server.url("/").toString(),
        "gc-key",
        TEMPLATE_ID,
        DELEGATED_ADMIN_TEMPLATE_ID,
        new FamProperties.Integration.Timeouts(Duration.ofSeconds(2), Duration.ofSeconds(2)));

    service = new GcNotifyEmailService(
        new FamProperties("dev", null, new FamProperties.Integration(null, null, config), null),
        new RestClientFactory(), new UpstreamErrorTranslator(objectMapper), objectMapper);
    service.initClient();
  }

  @AfterEach
  void tearDown() throws IOException {
    server.shutdown();
  }

  private static GcNotifyGrantAccessEmailParams params(
      List<FamForestClientDto> organizations, String contactEmail) {
    return new GcNotifyGrantAccessEmailParams(
        "JSMITH", "Jane", "Smith",
        "Forest Operations Map",
        "Reviewer",
        organizations,
        contactEmail,
        "jane@example.com");
  }

  private JsonNode sendAndCapture(GcNotifyGrantAccessEmailParams params) throws Exception {
    server.enqueue(new MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"id\":\"notification-1\"}"));

    service.sendUserAccessGrantedEmail(params);

    RecordedRequest request = server.takeRequest();
    return objectMapper.readTree(request.getBody().readUtf8());
  }

  @Test
  @DisplayName("posts to the email endpoint with GC Notify's own auth scheme")
  void postsWithApiKeyScheme() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "application/json").setBody("{}"));

    service.sendUserAccessGrantedEmail(params(null, null));

    RecordedRequest request = server.takeRequest();
    assertThat(request.getMethod()).isEqualTo("POST");
    assertThat(request.getPath()).isEqualTo("/v2/notifications/email");
    // Not "Bearer" - GC Notify defines its own scheme.
    assertThat(request.getHeader("Authorization")).isEqualTo("ApiKey-v1 gc-key");
  }

  @Test
  @DisplayName("sends the template id and recipient")
  void sendsTemplateAndRecipient() throws Exception {
    JsonNode body = sendAndCapture(params(null, null));

    assertThat(body.get("template_id").asText()).isEqualTo(TEMPLATE_ID);
    assertThat(body.get("email_address").asText()).isEqualTo("jane@example.com");
  }

  @Test
  @DisplayName("passes the application description as the template's application_name")
  void usesDescriptionAsApplicationName() throws Exception {
    JsonNode personalisation = sendAndCapture(params(null, null)).get("personalisation");

    // The template variable is application_name, but the human-readable
    // description is what upstream deliberately supplied.
    assertThat(personalisation.get("application_name").asText()).isEqualTo("Forest Operations Map");
  }

  @Test
  @DisplayName("writes a self-contained sentence when the role has no organisation scope")
  void unscopedRoleText() throws Exception {
    JsonNode personalisation = sendAndCapture(params(null, null)).get("personalisation");

    assertThat(personalisation.get("application_role_granted_text").asText())
        .isEqualTo("You have been granted access to **Forest Operations Map** "
            + "with a **Reviewer** role.");
    // The template always renders this variable, so it must never be null.
    assertThat(personalisation.get("organization_list_text").asText()).isEmpty();
  }

  @Test
  @DisplayName("ends with a colon and lists organisations when the role is scoped")
  void scopedRoleText() throws Exception {
    JsonNode personalisation = sendAndCapture(params(List.of(
        new FamForestClientDto("AKIECA EXPLORERS LTD.", "00001011", null),
        new FamForestClientDto("BEAVER LUMBER", "00001012", null)), null))
        .get("personalisation");

    assertThat(personalisation.get("application_role_granted_text").asText())
        .isEqualTo("You have been granted access to **Forest Operations Map** "
            + "with a **Reviewer** role for the following organizations:");
    assertThat(personalisation.get("organization_list_text").asText())
        .isEqualTo("* **AKIECA EXPLORERS LTD.** (Client number: 00001011)\n"
            + "* **BEAVER LUMBER** (Client number: 00001012)");
  }

  @Test
  @DisplayName("names the contact when a team email is supplied")
  void contactMessageWithEmail() throws Exception {
    JsonNode personalisation =
        sendAndCapture(params(null, "team@gov.bc.ca")).get("personalisation");

    assertThat(personalisation.get("contact_message").asText())
        .isEqualTo("Please contact your administrator team@gov.bc.ca "
            + "if you have any issues accessing the application.");
  }

  @Test
  @DisplayName("falls back to a generic contact message with no team email")
  void contactMessageWithoutEmail() throws Exception {
    JsonNode personalisation = sendAndCapture(params(null, null)).get("personalisation");

    assertThat(personalisation.get("contact_message").asText())
        .isEqualTo("Please contact your administrator "
            + "if you have any issues accessing the application.");
  }

  @Test
  @DisplayName("uses a different template for a delegated admin grant")
  void delegatedAdminUsesItsOwnTemplate() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "application/json").setBody("{}"));

    service.sendDelegatedAdminGrantedEmail(params(null, null), false);

    JsonNode body = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
    assertThat(body.get("template_id").asText()).isEqualTo(DELEGATED_ADMIN_TEMPLATE_ID);
  }

  @Test
  @DisplayName("includes the terms-of-use line only for a BCeID delegated admin")
  void termsTextOnlyForBceid() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "application/json").setBody("{}"));
    service.sendDelegatedAdminGrantedEmail(params(null, null), true);
    JsonNode bceid = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
    assertThat(bceid.get("personalisation").get("terms_conditions_comply_text").asText())
        .isNotEmpty();

    server.enqueue(new MockResponse().setResponseCode(201)
        .setHeader("Content-Type", "application/json").setBody("{}"));
    service.sendDelegatedAdminGrantedEmail(params(null, null), false);
    JsonNode idir = objectMapper.readTree(server.takeRequest().getBody().readUtf8());
    // Always sent, but empty - GC Notify cannot render a missing variable.
    assertThat(idir.get("personalisation").has("terms_conditions_comply_text")).isTrue();
    assertThat(idir.get("personalisation").get("terms_conditions_comply_text").asText())
        .isEmpty();
  }

  @Test
  @DisplayName("reads GC Notify\'s errors[] payload shape")
  void parsesGcNotifyErrorShape() {
    // GC Notify does not use failureCode/message; it uses errors[0].error/message.
    server.enqueue(new MockResponse().setResponseCode(400)
        .setHeader("Content-Type", "application/json")
        .setBody("""
            {"errors":[{"error":"ValidationError",
                        "message":"email_address Not a valid email address"}],
             "status_code":400}"""));

    assertThatThrownBy(() -> service.sendUserAccessGrantedEmail(params(null, null)))
        .isInstanceOf(UpstreamException.class)
        .extracting("status", "failureCode", "message")
        .containsExactly(HttpStatus.BAD_REQUEST, "ValidationError",
            "email_address Not a valid email address");
  }

  @Test
  @DisplayName("reports a rejected API key as 500, not as the caller's 403")
  void forbiddenBecomesInternalError() {
    server.enqueue(new MockResponse().setResponseCode(403)
        .setHeader("Content-Type", "application/json")
        .setBody("{\"errors\":[{\"error\":\"AuthError\",\"message\":\"Invalid token\"}]}"));

    assertThatThrownBy(() -> service.sendUserAccessGrantedEmail(params(null, null)))
        .isInstanceOf(UpstreamException.class)
        .extracting("status")
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}

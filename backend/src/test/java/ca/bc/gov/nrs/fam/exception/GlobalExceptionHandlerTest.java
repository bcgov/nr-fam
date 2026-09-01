package ca.bc.gov.nrs.fam.exception;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.bc.gov.nrs.fam.constants.ErrorCode;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.constants.UserTypeConverter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Locks down the three error response shapes the Vue frontend branches on.
 *
 * <p>{@code utils/ApiUtils.ts} tests {@code Array.isArray(detail)} to tell a
 * validation failure from a business error, so the difference between an object
 * {@code detail} and an array {@code detail} is part of the contract, not an
 * implementation detail.
 */
@DisplayName("GlobalExceptionHandler response shapes")
class GlobalExceptionHandlerTest {

  /**
   * The converter is registered the way production registers it, so these
   * exercise Spring's own parameter binding rather than the converter alone.
   * The bug this covers was never in the converter - there wasn't one - it was
   * in what Spring did without it.
   */
  private static FormattingConversionService conversionService() {
    FormattingConversionService service = new DefaultFormattingConversionService();
    service.addConverter(new UserTypeConverter());
    return service;
  }

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new TestController())
      .setControllerAdvice(new GlobalExceptionHandler())
      .setConversionService(conversionService())
      .build();

  record Payload(@NotBlank String name) {}

  @RestController
  static class TestController {

    @GetMapping("/user-type")
    String userType(@org.springframework.web.bind.annotation.RequestParam UserType type) {
      return type.name();
    }

    @GetMapping("/business-error")
    String businessError() {
      throw FamHttpException.forbidden(ErrorCode.SELF_GRANT_PROHIBITED,
          "Altering permission privilege to self is not allowed.");
    }

    @GetMapping("/upstream-timeout")
    String upstreamTimeout() {
      throw new UpstreamException(HttpStatus.GATEWAY_TIMEOUT, ErrorCode.UPSTREAM_TIMEOUT,
          "Upstream service timed out.", "forest-client-api");
    }

    @GetMapping("/upstream-no-code")
    String upstreamWithoutFailureCode() {
      throw new UpstreamException(HttpStatus.BAD_GATEWAY, null, "Something went wrong", "idim");
    }

    @GetMapping("/boom")
    String boom() {
      throw new IllegalStateException("internal detail that must not leak");
    }

    @PostMapping("/validated")
    String validated(@Valid @RequestBody Payload payload) {
      return payload.name();
    }
  }

  @Test
  @DisplayName("business errors serialise as an OBJECT detail with code and description")
  void businessErrorShape() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.get("/business-error"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail.code").value(ErrorCode.SELF_GRANT_PROHIBITED))
        .andExpect(jsonPath("$.detail.description")
            .value("Altering permission privilege to self is not allowed."))
        .andExpect(jsonPath("$.detail").isMap());
  }

  @Test
  @DisplayName("validation errors serialise as an ARRAY detail of loc/msg/type")
  void validationErrorShape() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.post("/validated")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").isArray())
        .andExpect(jsonPath("$.detail[0].loc").isArray())
        .andExpect(jsonPath("$.detail[0].loc[0]").value("body"))
        .andExpect(jsonPath("$.detail[0].loc[1]").value("name"))
        .andExpect(jsonPath("$.detail[0].msg").exists())
        .andExpect(jsonPath("$.detail[0].type").exists());
  }

  @Test
  @DisplayName("upstream failures serialise as failureCode/message, not detail")
  void upstreamErrorShape() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.get("/upstream-timeout"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(jsonPath("$.failureCode").value(ErrorCode.UPSTREAM_TIMEOUT))
        .andExpect(jsonPath("$.message").value("Upstream service timed out."))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }

  @Test
  @DisplayName("a null failureCode is still present as a key, as it was in Python")
  void upstreamErrorKeepsNullFailureCode() throws Exception {
    // Upstream could legitimately return no failure code. Python emitted
    // {"failureCode": null, ...} rather than omitting the key, so a client
    // reading response.failureCode gets null instead of undefined.
    mockMvc.perform(MockMvcRequestBuilders.get("/upstream-no-code"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.failureCode").hasJsonPath())
        .andExpect(jsonPath("$.failureCode").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.message").value("Something went wrong"));
  }

  @Test
  @DisplayName("unhandled exceptions return JSON and withhold the internal message")
  void unhandledErrorShape() throws Exception {
    mockMvc.perform(MockMvcRequestBuilders.get("/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail.code").value(ErrorCode.UNKNOWN_STATE))
        .andExpect(jsonPath("$.detail.description").value("Internal Server Error"));
  }

  @Test
  @DisplayName("reads a user type off the query string by its wire code")
  void readsUserTypeFromQueryString() throws Exception {
    /*
        BCEID is published as BCEID_BUS, and Spring's default enum conversion
        matches constant names - so this arrived as "No enum constant
        UserType.BCEID_BUS" and a 500. IDIR hid it, its code and constant name
        being the same string, which is why it took a BCeID user's history to
        surface it.
    */
    mockMvc.perform(MockMvcRequestBuilders.get("/user-type").param("type", "BCEID_BUS"))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().string("BCEID"));

    mockMvc.perform(MockMvcRequestBuilders.get("/user-type").param("type", "IDIR"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("answers an unreadable parameter as the caller's error, not a server fault")
  void unreadableParameterIsNotAServerError() {
    // 422 with the parameter named. A 500 saying "Internal Server Error" both
    // misattributes the fault and hides which value was wrong.
    try {
      mockMvc.perform(MockMvcRequestBuilders.get("/user-type").param("type", "BCSC"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.detail[0].loc[0]").value("query"))
          .andExpect(jsonPath("$.detail[0].loc[1]").value("type"))
          .andExpect(jsonPath("$.detail[0].msg", Matchers.containsString("BCSC")));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}

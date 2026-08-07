package ca.bc.gov.nrs.fam.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Lets a controller take a {@link Requester} parameter directly, the way FastAPI
 * handlers took {@code requester: RequesterSchema = Depends(get_current_requester)}.
 *
 * <p>Resolution hits the database, so it is cached per request - several guards
 * on one endpoint would otherwise each re-resolve the same user.
 */
@Component
@RequiredArgsConstructor
public class RequesterArgumentResolver implements HandlerMethodArgumentResolver {

  private static final String REQUEST_ATTRIBUTE = RequesterArgumentResolver.class.getName();

  private final RequesterService requesterService;

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return Requester.class.equals(parameter.getParameterType());
  }

  @Override
  public Object resolveArgument(
      MethodParameter parameter,
      ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest,
      WebDataBinderFactory binderFactory) {

    Object cached = webRequest.getAttribute(REQUEST_ATTRIBUTE, NativeWebRequest.SCOPE_REQUEST);
    if (cached instanceof Requester requester) {
      return requester;
    }

    Requester requester = requesterService.currentRequester();
    webRequest.setAttribute(REQUEST_ATTRIBUTE, requester, NativeWebRequest.SCOPE_REQUEST);
    return requester;
  }
}

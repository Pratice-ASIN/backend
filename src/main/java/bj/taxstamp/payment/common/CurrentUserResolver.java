package bj.taxstamp.payment.common;

import java.util.regex.Pattern;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Injecte {@link UsagerCourant} dans les contrôleurs à partir de l'en-tête
 * {@code X-User-Id}. Requête refusée (401) si l'en-tête est absent ou mal formé.
 */
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-User-Id";

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_.-]{3,64}");

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CurrentUser.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String value = webRequest.getHeader(HEADER);
        if (value == null || !FORMAT.matcher(value.trim()).matches()) {
            throw BusinessError.userNotIdentified();
        }
        return new CurrentUser(value.trim());
    }
}

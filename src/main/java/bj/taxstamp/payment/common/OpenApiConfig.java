package bj.taxstamp.payment.common;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Documentation OpenAPI (Swagger UI sur /swagger-ui.html).
 *
 * {@link CurrentUser} est injecté depuis l'en-tête {@code X-User-Id} : on le masque des
 * paramètres et on le déclare comme schéma d'identification, renseignable une fois
 * via le bouton "Authorize".
 */
@Configuration
public class OpenApiConfig {

    private static final String USER_SCHEME = "userId";

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Paiement du timbre fiscal")
                        .version("1.0.0")
                        .description("Paiement du timbre fiscal par mobile money (MTN, MOOV, CELTIIS)."))
                .components(new Components().addSecuritySchemes(USER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(CurrentUserResolver.HEADER)));
    }

    /** Seules les routes usager exigent X-User-Id (pas les rappels opérateur ni le simulateur). */
    @Bean
    public OpenApiCustomizer userHeaderOnUserRoutes() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            boolean userRoute = path.startsWith("/api/document-requests") || path.startsWith("/api/payments");
            if (userRoute) {
                item.readOperations().forEach(op -> op.addSecurityItem(new SecurityRequirement().addList(USER_SCHEME)));
            }
        });
    }
}

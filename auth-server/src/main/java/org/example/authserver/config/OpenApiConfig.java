package org.example.authserver.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI authServerOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Auth Server API")
                        .description("Authorization Server endpoints for OAuth2/OIDC and login flow")
                        .version("v1")
                        .contact(new Contact().name("Auth Demo Team"))
                        .license(new License().name("Apache 2.0")));
    }
}


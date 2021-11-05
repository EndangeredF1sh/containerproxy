package eu.openanalytics.containerproxy.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
import org.springframework.security.oauth2.config.annotation.web.configuration.ResourceServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configurers.ResourceServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.authentication.BearerTokenExtractor;
import org.springframework.security.oauth2.provider.token.*;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.jwk.JwkTokenStore;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "proxy.oauth2.resource-id")
@EnableResourceServer
public class APISecurityConfig extends ResourceServerConfigurerAdapter {

    private final Environment environment;

    public APISecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void configure(HttpSecurity http) throws Exception {
        http.antMatcher("/api/**").authorizeRequests().anyRequest().authenticated().and().httpBasic();
    }

    @Override
    public void configure(ResourceServerSecurityConfigurer resources) throws Exception {
        resources
            .tokenExtractor(new CookieTokenExtractor())
            .resourceId(environment.getProperty("proxy.oauth2.resource-id"));
    }

    @Bean
    public JwtAccessTokenConverter jwtAccessTokenConverter() {
        JwtAccessTokenConverter converter = new JwtAccessTokenConverter();
        DefaultAccessTokenConverter tokenConverter = new DefaultAccessTokenConverter();
        tokenConverter.setUserTokenConverter(new DefaultUserAuthenticationConverter() {
            @Override
            public Authentication extractAuthentication(Map<String, ?> map) {
                Authentication auth = super.extractAuthentication(map);
                if (auth == null) {
                    // If 'user_name' is not available, use 'sub' instead.
                    String principal = String.valueOf(map.get("sub"));
                    return new UsernamePasswordAuthenticationToken(principal, "N/A", null);
                }
                return auth;
            }
        });
        converter.setAccessTokenConverter(tokenConverter);
        return converter;
    }

    @Bean
    @ConditionalOnMissingBean(TokenStore.class)
    public TokenStore jwkTokenStore() {
        return new JwkTokenStore(environment.getProperty("proxy.oauth2.jwks-url"), jwtAccessTokenConverter());
    }

    @Bean
    @ConditionalOnMissingBean(ResourceServerTokenServices.class)
    public DefaultTokenServices jwkTokenServices(TokenStore jwkTokenStore) {
        DefaultTokenServices services = new DefaultTokenServices();
        services.setTokenStore(jwkTokenStore);
        return services;
    }

    /**
     * Custom token extractor that also inspects cookies in addition to
     * the Authorization header and request parameters.
     */
    private static class CookieTokenExtractor extends BearerTokenExtractor {
        @Override
        protected String extractToken(HttpServletRequest request) {
            String token = super.extractToken(request);
            if (token == null && request.getCookies() != null) {
                token = Arrays.stream(request.getCookies())
                    .filter(c -> c.getName().equals("access_token")).findAny()
                    .map(c -> c.getValue()).orElse(null);
            }
            return token;
        }
    }
}

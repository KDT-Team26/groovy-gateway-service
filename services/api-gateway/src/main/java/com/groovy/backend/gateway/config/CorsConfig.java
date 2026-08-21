package com.groovy.backend.gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 게이트웨이의 라우트는 RequestMappingHandlerMapping이 아니라 RouterFunctionMapping을 통해
 * 디스패치되므로, WebMvcConfigurer#addCorsMappings는 적용되지 않는다. 서블릿 필터 레벨에서
 * 동작하는 CorsFilter를 직접 등록해야 모든 라우트에 CORS가 적용된다.
 */
@Configuration
public class CorsConfig {

	@Bean
	public CorsFilter corsFilter(
		@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins
	) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);

		return new CorsFilter(source);
	}
}

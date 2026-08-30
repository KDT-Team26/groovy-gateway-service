package com.groovy.backend.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.groovy.backend.observability.TracingConfig;

/**
 * 공통 코드 분리(groovy-common) 후: observability 모듈의 TracingConfig(@Configuration)가
 * 이 서비스 base 패키지 밖이라 @Import 로 가져온다. (api-gateway 는 라우팅 전용, JPA/outbox 없음)
 */
@SpringBootApplication
@Import(TracingConfig.class)
public class ApiGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiGatewayApplication.class, args);
	}
}

package com.groovy.backend.gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.EventListener;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.otel.bridge.Slf4JBaggageEventListener;
import io.micrometer.tracing.otel.bridge.Slf4JEventListener;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * MSA 전환 Phase 12: 분산 트레이싱.
 *
 * Boot 4.1의 spring-boot-opentelemetry/spring-boot-micrometer-tracing은
 * OpenTelemetrySdk의 "틀"(Resource, ContextPropagators를 조합해 OpenTelemetrySdk 빈을 만드는
 * 오토컨피그)만 제공하고, 실제 SdkTracerProvider(OTLP 익스포터 포함)와 Micrometer Tracer 빈은
 * 만들어주지 않는다 — 두 모듈의 jar를 직접 열어 AutoConfiguration.imports를 확인해서 검증한
 * 사실이다(OTLP 로그 익스포트(OtlpLoggingAutoConfiguration)는 자동 설정되지만 트레이스 익스포트는
 * 별도 오토컨피그가 없다). 그래서 여기서 SdkTracerProvider/ContextPropagators/Tracer/Propagator를
 * 직접 조립한다 — SdkTracerProvider와 ContextPropagators를 빈으로 노출하면 Boot의
 * OpenTelemetrySdkAutoConfiguration이 이 둘을 모아 OpenTelemetrySdk 빈을 만들어준다.
 */
@Configuration
public class TracingConfig {

	// Context 전파(비동기 스레드 이동 포함) 시 MDC에 traceId/spanId를 채우는 리스너.
	// LogFields.TRACE_ID와 동일한 기본 키("traceId")를 쓰므로 별도 매핑이 필요 없다.
	private final List<EventListener> eventListeners = List.of(
		new Slf4JEventListener(),
		new Slf4JBaggageEventListener(List.of())
	);

	public TracingConfig() {
		// 앱 기동 중 한 번만 등록하면 된다(전역 정적 등록).
		ContextStorage.addWrapper(new EventPublishingContextWrapper(
			event -> eventListeners.forEach(listener -> listener.onEvent(event))));
	}

	@Bean
	public SdkTracerProvider sdkTracerProvider(
		Resource resource,
		@Value("${management.otlp.tracing.endpoint}") String otlpEndpoint,
		@Value("${management.tracing.sampling.probability:1.0}") double samplingProbability
	) {
		OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
			.setEndpoint(otlpEndpoint)
			.build();

		return SdkTracerProvider.builder()
			.setResource(resource)
			.setSampler(Sampler.traceIdRatioBased(samplingProbability))
			.addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
			.build();
	}

	@Bean
	public ContextPropagators contextPropagators() {
		return ContextPropagators.create(TextMapPropagator.composite(
			W3CTraceContextPropagator.getInstance(),
			W3CBaggagePropagator.getInstance()));
	}

	@Bean
	public Tracer tracer(OpenTelemetrySdk openTelemetrySdk, @Value("${spring.application.name}") String serviceName) {
		OtelTracer.EventPublisher eventPublisher = event ->
			eventListeners.forEach(listener -> listener.onEvent(event));
		return new OtelTracer(openTelemetrySdk.getTracer(serviceName), new OtelCurrentTraceContext(), eventPublisher);
	}

	@Bean
	public Propagator propagator(OpenTelemetrySdk openTelemetrySdk, @Value("${spring.application.name}") String serviceName) {
		return new OtelPropagator(openTelemetrySdk.getPropagators(), openTelemetrySdk.getTracer(serviceName));
	}
}

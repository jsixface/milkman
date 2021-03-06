package milkman.plugin.test;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import milkman.domain.Environment;
import milkman.domain.RequestContainer;
import milkman.domain.ResponseContainer;
import milkman.plugin.test.domain.TestAspect;
import milkman.plugin.test.domain.TestContainer;
import milkman.plugin.test.domain.TestResultAspect;
import milkman.plugin.test.domain.TestResultAspect.TestResultEvent;
import milkman.plugin.test.domain.TestResultContainer;
import milkman.ui.plugin.PluginRequestExecutor;
import milkman.ui.plugin.Templater;
import milkman.utils.AsyncResponseControl;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ReplayProcessor;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.util.Map;
import java.util.Optional;

import static milkman.plugin.test.domain.TestResultAspect.TestResultState.*;

@Slf4j
@RequiredArgsConstructor
public class TestRunner {

	private final PluginRequestExecutor executor;

	public ResponseContainer executeRequest(TestContainer request,
											Templater templater,
											AsyncResponseControl.AsyncControl asyncControl) {

		var testAspect = request.getAspect(TestAspect.class)
				.orElseThrow(() -> new IllegalArgumentException("Missing test aspect"));

		asyncControl.triggerReqeuestStarted();


		Flux<TestResultEvent> replay = ReplayProcessor.create(sink -> {
			Flux.fromIterable(testAspect.getRequests())
					.index()
					.filter(t -> !t.getT2().isSkip())
					.flatMap(tuple -> Mono.justOrEmpty(executor.getDetails(tuple.getT2().getId()).map(r -> tuple.mapT2(id -> r))))
					.doOnNext(tuple -> sink.next(new TestResultEvent(tuple.getT1().toString(), tuple.getT2().getName(), STARTED, Map.of())))
					.flatMap(tuple -> execute(tuple, getOverrideEnvironment(testAspect), sink))
					.onErrorContinue(err -> !testAspect.isStopOnFirstFailure(), (err, obj) -> {/* silently skip errors, they where signaled already */})
//				.switchIfEmpty(Mono.defer(() -> {
//					log.error("Request could not be found");
//					return Mono.just(new TestResultEvent("", "", TestResultAspect.TestResultState.EXCEPTION));
//				}))
					.doFinally(s -> {
						asyncControl.triggerRequestSucceeded();
						sink.complete();
					})
					.subscribeOn(Schedulers.parallel())
					.publish().connect();
		});

		var container = new TestResultContainer();
		container.getAspects().add(new TestResultAspect(replay));
		return container;
	}


	private Environment getOverrideEnvironment(TestAspect testAspect){
		var environment = new Environment("override");
		environment.setActive(true);
		testAspect.getEnvironmentOverride().forEach(entry -> environment.setOrAdd(entry.getName(), entry.getValue()));
		return environment;
	}

	private Mono<Void> execute(Tuple2<Long, RequestContainer> request, Environment overrideEnv, FluxSink<TestResultEvent> replay) {
		return Mono.defer(() -> Mono.just(executor.executeRequest(request.getT2(), Optional.of(overrideEnv))))
				.flatMap(res -> Mono.fromFuture(res.getStatusInformations()))
				.doOnNext(si -> replay.next(new TestResultEvent(request.getT1().toString(), request.getT2().getName(), SUCCEEDED, si)))
				.doOnError(t -> replay.next(new TestResultEvent(request.getT1().toString(), request.getT2().getName(), FAILED, Map.of("exception", t.toString()))))
				.then();
	}
}

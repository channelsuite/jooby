/**
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Copyright 2014 Edgar Espina
 */
package io.jooby.internal;

import io.jooby.Context;
import io.jooby.ExecutionMode;
import io.jooby.Reified;
import io.jooby.Route;
import io.jooby.Route.Handler;
import io.jooby.internal.handler.CompletionStageHandler;
import io.jooby.internal.handler.DefaultHandler;
import io.jooby.internal.handler.DetachHandler;
import io.jooby.internal.handler.FileChannelHandler;
import io.jooby.internal.handler.InputStreamHandler;
import io.jooby.internal.handler.NoopHandler;
import io.jooby.internal.handler.reactive.ReactivePublisherHandler;
import io.jooby.internal.handler.WorkerExecHandler;
import io.jooby.internal.handler.reactive.JavaFlowPublisher;
import io.jooby.internal.handler.reactive.ReactorFluxHandler;
import io.jooby.internal.handler.reactive.RxMaybeHandler;
import io.jooby.internal.handler.reactive.ReactorMonoHandler;
import io.jooby.internal.handler.reactive.ObservableHandler;
import io.jooby.internal.handler.WorkerHandler;
import io.jooby.internal.handler.reactive.RxFlowableHandler;
import io.jooby.internal.handler.reactive.RxSingleHandler;

import java.io.File;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;

public class Pipeline {

  public static Handler compute(ClassLoader loader, Route route, ExecutionMode mode) {
    return provider(loader, Reified.rawType(route.returnType())).apply(mode, route);
  }

  private static BiFunction<ExecutionMode, Route, Handler> provider(ClassLoader loader,
      Class type) {
    if (CompletionStage.class.isAssignableFrom(type)) {
      return Pipeline::completableFuture;
    }
    /** Rx 2: */
    // Single:
    Optional<Class> single = loadClass(loader, "io.reactivex.Single");
    if (single.isPresent()) {
      if (single.get().isAssignableFrom(type)) {
        return Pipeline::single;
      }
    }
    // Maybe:
    Optional<Class> maybe = loadClass(loader, "io.reactivex.Maybe");
    if (maybe.isPresent()) {
      if (maybe.get().isAssignableFrom(type)) {
        return Pipeline::rxMaybe;
      }
    }
    // Flowable:
    Optional<Class> flowable = loadClass(loader, "io.reactivex.Flowable");
    if (flowable.isPresent()) {
      if (flowable.get().isAssignableFrom(type)) {
        return Pipeline::rxFlowable;
      }
    }
    // Observable:
    Optional<Class> observable = loadClass(loader, "io.reactivex.Observable");
    if (observable.isPresent()) {
      if (observable.get().isAssignableFrom(type)) {
        return Pipeline::rxObservable;
      }
    }
    // Disposable
    Optional<Class> disposable = loadClass(loader, "io.reactivex.disposables.Disposable");
    if (disposable.isPresent()) {
      if (disposable.get().isAssignableFrom(type)) {
        return Pipeline::rxDisposable;
      }
    }
    /** Reactor: */
    // Flux:
    Optional<Class> flux = loadClass(loader, "reactor.core.publisher.Flux");
    if (flux.isPresent()) {
      if (flux.get().isAssignableFrom(type)) {
        return Pipeline::reactorFlux;
      }
    }
    // Mono:
    Optional<Class> mono = loadClass(loader, "reactor.core.publisher.Mono");
    if (mono.isPresent()) {
      if (mono.get().isAssignableFrom(type)) {
        return Pipeline::reactorMono;
      }
    }
    /** Flow API + ReactiveStream: */
    Optional<Class> publisher = loadClass(loader, "org.reactivestreams.Publisher");
    if (publisher.isPresent()) {
      if (publisher.get().isAssignableFrom(type)) {
        return Pipeline::reactivePublisher;
      }
    }
    if (Flow.Publisher.class.isAssignableFrom(type)) {
      return Pipeline::javaFlowPublisher;
    }
    if (Context.class.isAssignableFrom(type)) {
      return (mode, route) -> next(mode, route.executor(), new NoopHandler(route.pipeline()),
          true);
    }

    if (InputStream.class.isAssignableFrom(type)) {
      return (mode, route) -> next(mode, route.executor(), new InputStreamHandler(route.pipeline()),
          true);
    }

    if (FileChannel.class.isAssignableFrom(type) || Path.class.isAssignableFrom(type) || File.class
        .isAssignableFrom(type)) {
      return (mode, route) -> next(mode, route.executor(), new FileChannelHandler(route.pipeline()),
          false);
    }

    return (mode, route) -> next(mode, route.executor(), new DefaultHandler(route.pipeline()),
        true);
  }

  private static Handler completableFuture(ExecutionMode mode, Route next) {
    return next(mode, next.executor(),
        new DetachHandler(new CompletionStageHandler(next.pipeline())), false);
  }

  private static Handler rxFlowable(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new RxFlowableHandler(next.pipeline())),
        false);
  }

  private static Handler reactivePublisher(ExecutionMode mode, Route next) {
    return next(mode, next.executor(),
        new DetachHandler(new ReactivePublisherHandler(next.pipeline())),
        false);
  }

  private static Handler rxDisposable(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new NoopHandler(next.pipeline())),
        false);
  }

  private static Handler rxObservable(ExecutionMode mode, Route next) {
    return next(mode, next.executor(),
        new DetachHandler(new ObservableHandler(next.pipeline())),
        false);
  }

  private static Handler reactorFlux(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new ReactorFluxHandler(next.pipeline())),
        false);
  }

  private static Handler reactorMono(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new ReactorMonoHandler(next.pipeline())),
        false);
  }

  private static Handler javaFlowPublisher(ExecutionMode mode, Route next) {
    return next(mode, next.executor(),
        new DetachHandler(new JavaFlowPublisher(next.pipeline())), false);
  }

  private static Handler single(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new RxSingleHandler(next.pipeline())),
        false);
  }

  private static Handler rxMaybe(ExecutionMode mode, Route next) {
    return next(mode, next.executor(), new DetachHandler(new RxMaybeHandler(next.pipeline())),
        false);
  }

  private static Handler next(ExecutionMode mode, Executor executor, Handler handler,
      boolean blocking) {
    if (executor == null) {
      if (mode == ExecutionMode.WORKER) {
        return new WorkerHandler(handler);
      }
      if (mode == ExecutionMode.DEFAULT && blocking) {
        return new WorkerHandler(handler);
      }
      return handler;
    }
    return new WorkerExecHandler(handler, executor);
  }

  private static Optional<Class> loadClass(ClassLoader loader, String name) {
    try {
      return Optional.of(loader.loadClass(name));
    } catch (ClassNotFoundException x) {
      return Optional.empty();
    }
  }
}

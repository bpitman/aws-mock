package com.pcpitman.mock.redis

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

import com.typesafe.scalalogging.LazyLogging

import io.lettuce.core.RedisFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.internal.AsyncCloseable

object MockRedisClient {
  def newProxy(): StatefulRedisConnection[String, String] = {
    val mock = new MockRedisClient()
    val commandsProxy = createAsyncCommandsProxy(mock)
    createConnectionProxy(commandsProxy)
  }

  private def createAsyncCommandsProxy(
    mock: MockRedisClient
  ): RedisAsyncCommands[String, String] = {
    val ctype = classOf[RedisAsyncCommands[String, String]]
    val handler = new InvocationHandler() {
      override def invoke(proxy: Object, method: Method, args: Array[Any]): Any = {
        try {
          val m = mock.getClass().getMethod(method.getName(), method.getParameterTypes: _*)
          if (args == null) m.invoke(mock) else m.invoke(mock, args: _*)
        } catch {
          case e: NoSuchMethodException =>
            throw new UnsupportedOperationException(s"Mock ${method.getName()} not implemented")
        }
      }
    }
    Proxy
      .newProxyInstance(ctype.getClassLoader(), Array(ctype), handler)
      .asInstanceOf[RedisAsyncCommands[String, String]]
  }

  private def createConnectionProxy(
    commands: RedisAsyncCommands[String, String]
  ): StatefulRedisConnection[String, String] = {
    val ctype = classOf[StatefulRedisConnection[String, String]]
    val handler = new InvocationHandler() {
      override def invoke(proxy: Object, method: Method, args: Array[Any]): Any = {
        method.getName match {
          case "async" => commands
          case "close" => () // no-op
          case _ =>
            throw new UnsupportedOperationException(
              s"Mock connection ${method.getName()} not implemented"
            )
        }
      }
    }
    Proxy
      .newProxyInstance(
        ctype.getClassLoader(),
        Array(ctype, classOf[AutoCloseable], classOf[AsyncCloseable]),
        handler
      )
      .asInstanceOf[StatefulRedisConnection[String, String]]
  }
}

class MockRedisClient extends LazyLogging {

  private val store = new ConcurrentHashMap[String, String]()

  logger.info("Created mock RedisClient")

  private def toRedisFuture[T](value: T): RedisFuture[T] = {
    new MockRedisFuture[T](CompletableFuture.completedFuture(value))
  }

  // Parameter types use Object due to generic type erasure on RedisAsyncCommands<K, V>

  def get(key: Object): RedisFuture[String] = {
    toRedisFuture(store.get(key.asInstanceOf[String]))
  }

  def set(key: Object, value: Object): RedisFuture[String] = {
    store.put(key.asInstanceOf[String], value.asInstanceOf[String])
    toRedisFuture("OK")
  }

  def setex(key: Object, seconds: Long, value: Object): RedisFuture[String] = {
    // TTL not enforced in mock — just store the value
    store.put(key.asInstanceOf[String], value.asInstanceOf[String])
    toRedisFuture("OK")
  }

  def del(keys: Array[Object]): RedisFuture[java.lang.Long] = {
    var count = 0L
    keys.foreach { k =>
      if (store.remove(k.asInstanceOf[String]) != null) count += 1
    }
    toRedisFuture(java.lang.Long.valueOf(count))
  }

  def close(): Unit = {}
}

/** Minimal RedisFuture implementation backed by a completed CompletableFuture. */
private class MockRedisFuture[T](underlying: CompletableFuture[T]) extends RedisFuture[T] {
  override def getError: String = null
  override def await(timeout: Long, unit: TimeUnit): Boolean = true
  override def get(): T = underlying.get()
  override def get(timeout: Long, unit: TimeUnit): T = underlying.get(timeout, unit)
  override def isDone: Boolean = true
  override def isCancelled: Boolean = false
  override def cancel(mayInterruptIfRunning: Boolean): Boolean = false

  override def toCompletableFuture: CompletableFuture[T] = underlying
  override def thenApply[U](fn: java.util.function.Function[_ >: T, _ <: U]) =
    underlying.thenApply(fn)
  override def thenApplyAsync[U](fn: java.util.function.Function[_ >: T, _ <: U]) =
    underlying.thenApplyAsync(fn)
  override def thenApplyAsync[U](
    fn: java.util.function.Function[_ >: T, _ <: U],
    executor: java.util.concurrent.Executor
  ) = underlying.thenApplyAsync(fn, executor)
  override def thenAccept(action: java.util.function.Consumer[_ >: T]) =
    underlying.thenAccept(action)
  override def thenAcceptAsync(action: java.util.function.Consumer[_ >: T]) =
    underlying.thenAcceptAsync(action)
  override def thenAcceptAsync(
    action: java.util.function.Consumer[_ >: T],
    executor: java.util.concurrent.Executor
  ) = underlying.thenAcceptAsync(action, executor)
  override def thenRun(action: Runnable) = underlying.thenRun(action)
  override def thenRunAsync(action: Runnable) = underlying.thenRunAsync(action)
  override def thenRunAsync(action: Runnable, executor: java.util.concurrent.Executor) =
    underlying.thenRunAsync(action, executor)
  override def thenCombine[U, V](
    other: java.util.concurrent.CompletionStage[_ <: U],
    fn: java.util.function.BiFunction[_ >: T, _ >: U, _ <: V]
  ) = underlying.thenCombine(other, fn)
  override def thenCombineAsync[U, V](
    other: java.util.concurrent.CompletionStage[_ <: U],
    fn: java.util.function.BiFunction[_ >: T, _ >: U, _ <: V]
  ) = underlying.thenCombineAsync(other, fn)
  override def thenCombineAsync[U, V](
    other: java.util.concurrent.CompletionStage[_ <: U],
    fn: java.util.function.BiFunction[_ >: T, _ >: U, _ <: V],
    executor: java.util.concurrent.Executor
  ) = underlying.thenCombineAsync(other, fn, executor)
  override def thenAcceptBoth[U](
    other: java.util.concurrent.CompletionStage[_ <: U],
    action: java.util.function.BiConsumer[_ >: T, _ >: U]
  ) = underlying.thenAcceptBoth(other, action)
  override def thenAcceptBothAsync[U](
    other: java.util.concurrent.CompletionStage[_ <: U],
    action: java.util.function.BiConsumer[_ >: T, _ >: U]
  ) = underlying.thenAcceptBothAsync(other, action)
  override def thenAcceptBothAsync[U](
    other: java.util.concurrent.CompletionStage[_ <: U],
    action: java.util.function.BiConsumer[_ >: T, _ >: U],
    executor: java.util.concurrent.Executor
  ) = underlying.thenAcceptBothAsync(other, action, executor)
  override def runAfterBoth(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable
  ) = underlying.runAfterBoth(other, action)
  override def runAfterBothAsync(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable
  ) = underlying.runAfterBothAsync(other, action)
  override def runAfterBothAsync(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable,
    executor: java.util.concurrent.Executor
  ) = underlying.runAfterBothAsync(other, action, executor)
  override def applyToEither[U](
    other: java.util.concurrent.CompletionStage[_ <: T],
    fn: java.util.function.Function[_ >: T, U]
  ) = underlying.applyToEither(other, fn)
  override def applyToEitherAsync[U](
    other: java.util.concurrent.CompletionStage[_ <: T],
    fn: java.util.function.Function[_ >: T, U]
  ) = underlying.applyToEitherAsync(other, fn)
  override def applyToEitherAsync[U](
    other: java.util.concurrent.CompletionStage[_ <: T],
    fn: java.util.function.Function[_ >: T, U],
    executor: java.util.concurrent.Executor
  ) = underlying.applyToEitherAsync(other, fn, executor)
  override def acceptEither(
    other: java.util.concurrent.CompletionStage[_ <: T],
    action: java.util.function.Consumer[_ >: T]
  ) = underlying.acceptEither(other, action)
  override def acceptEitherAsync(
    other: java.util.concurrent.CompletionStage[_ <: T],
    action: java.util.function.Consumer[_ >: T]
  ) = underlying.acceptEitherAsync(other, action)
  override def acceptEitherAsync(
    other: java.util.concurrent.CompletionStage[_ <: T],
    action: java.util.function.Consumer[_ >: T],
    executor: java.util.concurrent.Executor
  ) = underlying.acceptEitherAsync(other, action, executor)
  override def runAfterEither(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable
  ) = underlying.runAfterEither(other, action)
  override def runAfterEitherAsync(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable
  ) = underlying.runAfterEitherAsync(other, action)
  override def runAfterEitherAsync(
    other: java.util.concurrent.CompletionStage[_],
    action: Runnable,
    executor: java.util.concurrent.Executor
  ) = underlying.runAfterEitherAsync(other, action, executor)
  override def thenCompose[U](
    fn: java.util.function.Function[_ >: T, _ <: java.util.concurrent.CompletionStage[U]]
  ) = underlying.thenCompose(fn)
  override def thenComposeAsync[U](
    fn: java.util.function.Function[_ >: T, _ <: java.util.concurrent.CompletionStage[U]]
  ) = underlying.thenComposeAsync(fn)
  override def thenComposeAsync[U](
    fn: java.util.function.Function[_ >: T, _ <: java.util.concurrent.CompletionStage[U]],
    executor: java.util.concurrent.Executor
  ) = underlying.thenComposeAsync(fn, executor)
  override def exceptionally(
    fn: java.util.function.Function[Throwable, _ <: T]
  ) = underlying.exceptionally(fn)
  override def handle[U](
    fn: java.util.function.BiFunction[_ >: T, Throwable, _ <: U]
  ) = underlying.handle(fn)
  override def handleAsync[U](
    fn: java.util.function.BiFunction[_ >: T, Throwable, _ <: U]
  ) = underlying.handleAsync(fn)
  override def handleAsync[U](
    fn: java.util.function.BiFunction[_ >: T, Throwable, _ <: U],
    executor: java.util.concurrent.Executor
  ) = underlying.handleAsync(fn, executor)
  override def whenComplete(
    action: java.util.function.BiConsumer[_ >: T, _ >: Throwable]
  ) = underlying.whenComplete(action)
  override def whenCompleteAsync(
    action: java.util.function.BiConsumer[_ >: T, _ >: Throwable]
  ) = underlying.whenCompleteAsync(action)
  override def whenCompleteAsync(
    action: java.util.function.BiConsumer[_ >: T, _ >: Throwable],
    executor: java.util.concurrent.Executor
  ) = underlying.whenCompleteAsync(action, executor)
}

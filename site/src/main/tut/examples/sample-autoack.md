---
layout: docs
title:  "Single AutoAckConsumer"
number: 15
---

# Single AutoAckConsumer

Here we create a single `AutoAckConsumer`, a single `Publisher` and finally we publish two messages: a simple `String` message and a `Json` message by using the `fs2-rabbit-circe` extension.

```tut:book:silent
import java.nio.charset.StandardCharsets.UTF_8

import cats.data.Kleisli
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import dev.profunktor.fs2rabbit.config.declaration.DeclarationQueueConfig
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.json.Fs2JsonEncoder
import dev.profunktor.fs2rabbit.model.AckResult.Ack
import dev.profunktor.fs2rabbit.model.AmqpFieldValue.{LongVal, StringVal}
import dev.profunktor.fs2rabbit.model._
import fs2.{Pipe, Pure, Stream}

class AutoAckFlow[F[_]: Concurrent, A](
    consumer: Stream[F, AmqpEnvelope[A]],
    logger: Pipe[F, AmqpEnvelope[A], AckResult],
    publisher: AmqpMessage[String] => F[Unit]
) {

  import io.circe.generic.auto._

  case class Address(number: Int, streetName: String)
  case class Person(id: Long, name: String, address: Address)

  private val jsonEncoder = new Fs2JsonEncoder
  import jsonEncoder.jsonEncode

  val jsonPipe: Pipe[Pure, AmqpMessage[Person], AmqpMessage[String]] = _.map(jsonEncode[Person])

  val simpleMessage =
    AmqpMessage("Hey!", AmqpProperties(headers = Map("demoId" -> LongVal(123), "app" -> StringVal("fs2RabbitDemo"))))
  val classMessage = AmqpMessage(Person(1L, "Sherlock", Address(212, "Baker St")), AmqpProperties.empty)

  val flow: Stream[F, Unit] =
    Stream(
      Stream(simpleMessage).covary[F] evalMap publisher,
      Stream(classMessage).covary[F] through jsonPipe evalMap publisher,
      consumer.through(logger).evalMap(ack => Sync[F].delay(println(ack)))
    ).parJoin(3)

}

class AutoAckConsumerDemo[F[_]: Concurrent](R: Fs2Rabbit[F]) {

  private val queueName    = QueueName("testQ")
  private val exchangeName = ExchangeName("testEX")
  private val routingKey   = RoutingKey("testRK")
  implicit val stringMessageEncoder =
    Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s => s.copy(payload = s.payload.getBytes(UTF_8)).pure[F])

  def logPipe: Pipe[F, AmqpEnvelope[String], AckResult] = _.evalMap { amqpMsg =>
    Sync[F].delay(println(s"Consumed: $amqpMsg")).as(Ack(amqpMsg.deliveryTag))
  }

  val program: F[Unit] = {
    R.createConnectionChannel use { implicit channel =>
      for {
        _         <- R.declareQueue(DeclarationQueueConfig.default(queueName))
        _         <- R.declareExchange(exchangeName, ExchangeType.Topic)
        _         <- R.bindQueue(queueName, exchangeName, routingKey)
        publisher <- R.createPublisher[AmqpMessage[String]](exchangeName, routingKey)
        consumer  <- R.createAutoAckConsumer[String](queueName)
        _         <- new AutoAckFlow[F, String](consumer, logPipe, publisher).flow.compile.drain
      } yield ()
    }
  }
}
```

At the edge of out program we define our effect, `monix.eval.Task` in this case, and ask to evaluate the effects:

```tut:book:silent
import cats.data.NonEmptyList
import cats.effect.ExitCode
import cats.syntax.functor._
import dev.profunktor.fs2rabbit.config.{Fs2RabbitConfig, Fs2RabbitNodeConfig}
import dev.profunktor.fs2rabbit.interpreter.Fs2Rabbit
import dev.profunktor.fs2rabbit.resiliency.ResilientStream
import monix.eval.{Task, TaskApp}

object MonixAutoAckConsumer extends TaskApp {

  private val config: Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = "/",
    nodes = NonEmptyList.one(
      Fs2RabbitNodeConfig(
        host = "127.0.0.1",
        port = 5672
      )
    ),
    username = Some("guest"),
    password = Some("guest"),
    ssl = false,
    connectionTimeout = 3,
    requeueOnNack = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  override def run(args: List[String]): Task[ExitCode] =
    Fs2Rabbit[Task](config).flatMap { client =>
      ResilientStream
        .runF(new AutoAckConsumerDemo[Task](client).program)
        .as(ExitCode.Success)
    }

}
```

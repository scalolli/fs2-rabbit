/*
 * Copyright 2017 Fs2 Rabbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gvolpe.fs2rabbit.examples

import cats.effect.Effect
import com.github.gvolpe.fs2rabbit.interpreter.Fs2RabbitInterpreter
import com.github.gvolpe.fs2rabbit.json.Fs2JsonEncoder
import com.github.gvolpe.fs2rabbit.model._
import com.github.gvolpe.fs2rabbit.typeclasses.StreamEval
import fs2.{Pipe, Stream}

import scala.concurrent.ExecutionContext

class GenericDemo[F[_] : Effect](implicit F: Fs2RabbitInterpreter[F],
                                 EC: ExecutionContext,
                                 SE: StreamEval[F]) {

  private val queueName     = "testQ".as[QueueName]
  private val exchangeName  = "testEX".as[ExchangeName]
  private val routingKey    = "testRK".as[RoutingKey]

  def logPipe: Pipe[F, AmqpEnvelope, AckResult] = { streamMsg =>
    for {
      amqpMsg <- streamMsg
      _       <- SE.evalF[Unit](println(s"Consumed: $amqpMsg"))
    } yield Ack(amqpMsg.deliveryTag)
  }

  val program: Stream[F, Unit] =
    for {
      channel           <- F.createConnectionChannel
      _                 <- F.declareQueue(channel, queueName)
      _                 <- F.declareExchange(channel, exchangeName, ExchangeType.Topic)
      _                 <- F.bindQueue(channel, queueName, exchangeName, routingKey)
      ackerConsumer     <- F.createAckerConsumer(channel, queueName)
      (acker, consumer) = ackerConsumer
      publisher         <- F.createPublisher(channel, exchangeName, routingKey)
      result            <- new Flow(consumer, acker, logPipe, publisher).flow
    } yield result

}

class Flow[F[_] : Effect](consumer: StreamConsumer[F],
                          acker: StreamAcker[F],
                          logger: Pipe[F, AmqpEnvelope, AckResult],
                          publisher: StreamPublisher[F])
                          (implicit ec: ExecutionContext, SE: StreamEval[F]) {

  import io.circe.generic.auto._

  case class Address(number: Int, streetName: String)
  case class Person(id: Long, name: String, address: Address)

  private val jsonEncoder = new Fs2JsonEncoder[F]
  import jsonEncoder.jsonEncode

  val simpleMessage = AmqpMessage("Hey!", AmqpProperties(None, None, Map("demoId" -> LongVal(123), "app" -> StringVal("fs2RabbitDemo"))))
  val classMessage  = AmqpMessage(Person(1L, "Sherlock", Address(212, "Baker St")), AmqpProperties.empty)

  val flow: Stream[F, Unit] =
    Stream(
      Stream(simpleMessage).covary[F] to publisher,
      Stream(classMessage).covary[F]  through jsonEncode[Person] to publisher,
      consumer through logger to acker
    ).join(3)

}

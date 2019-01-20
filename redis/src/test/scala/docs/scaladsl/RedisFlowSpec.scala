/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.scaladsl

import java.lang

import akka.stream.KillSwitches
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped
import akka.streams.alpakka.redis.scaladsl.{RedisFlow, RedisSource}
import akka.streams.alpakka.redis.{RedisHKeyFields, _}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class RedisFlowSpec extends Specification with BeforeAfterAll with RedisSupport {

  sequential

  "scaladsl.RedisFlow" should {

    "insert single key/value" in assertAllStagesStopped {

      val key = Random.alphanumeric.take(10).mkString("")

      val result = Source
        .single(RedisKeyValue(key, "value1"))
        .via(RedisFlow.set(1, connection))
        .runWith(Sink.head[RedisOperationResult[RedisKeyValue[String, String], String]])
      Await.result(result, 5.seconds).result.get shouldEqual "OK"
    }

    "append single value" in assertAllStagesStopped {

      val key = Random.alphanumeric.take(10).mkString("")
      val result = Source
        .single(RedisKeyValue(key, "value1"))
        .via(RedisFlow.append(1, connection))
        .runWith(Sink.head[RedisOperationResult[RedisKeyValue[String, String], Long]])
      Await.result(result, 5.seconds).result.get shouldEqual 6L
    }

    "insert bulk of key/value" in assertAllStagesStopped {

      val sets = Seq[RedisKeyValue[String, String]](RedisKeyValue("key1", "value1"),
                                                    RedisKeyValue("key2", "value2"),
                                                    RedisKeyValue("key3", "value3"))

      val result = Source
        .fromIterator(() => sets.iterator)
        .grouped(2)
        .via(RedisFlow.mset(2, connection))
        .runWith(Sink.head[RedisOperationResult[Seq[RedisKeyValue[String, String]], String]])

      Await.result(result, 5.seconds).result.isSuccess shouldEqual true
    }

    "insert multiple values to single key with lpush" in assertAllStagesStopped {

      val key = Random.alphanumeric.take(10).mkString("")

      val result = Source
        .single(RedisKeyValues(key, Array("1", "2", "3")))
        .via(RedisFlow.lpush(1, connection))
        .runWith(Sink.head[RedisOperationResult[RedisKeyValues[String, String], Long]])

      Await.result(result, 5.seconds).result.get shouldEqual 3L

    }

    "implement pub/sub for single topic and return notification of single consumer" in {
      val topic = "topic20"
      RedisSource.subscribe(Seq(topic), pubSub).runWith(Sink.ignore)
      Thread.sleep(1000)
      val result = Source
        .single("Bla")
        .map(f => RedisPubSub(topic, f))
        .via(RedisFlow.publish[String, String](1, redisClient.connectPubSub().async().getStatefulConnection))
        .runWith(Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]])
      Await.result(result, 5.seconds).result.get shouldEqual 1L

    }

    "implement pub/sub for single topic and return published element from consumer" in assertAllStagesStopped {
      val topic = "topic0"

      val recievedMessage = RedisSource.subscribe(Seq(topic), pubSub).runWith(Sink.head[RedisPubSub[String, String]])
      Thread.sleep(1000)
      Source
        .single("Bla")
        .map(f => RedisPubSub(topic, f))
        .via(RedisFlow.publish[String, String](1, redisClient.connectPubSub().async().getStatefulConnection))
        .runWith(Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]])

      val result = Await.result(recievedMessage, 5.seconds)

      redisClient.connectPubSub().sync().unsubscribe(topic)

      result shouldEqual RedisPubSub(topic, "Bla")
    }

    "implement pub/sub for multiple values " in {

      val messages = Seq[RedisPubSub[String, String]](RedisPubSub("topic3", "value4"), RedisPubSub("topic2", "value2"))

      val receivedMessages: Future[immutable.Seq[RedisPubSub[String, String]]] =
        RedisSource.subscribe(Seq("topic3", "topic2"), pubSub).grouped(2).runWith(Sink.head)
      Thread.sleep(1000)
      Source
        .fromIterator(() => messages.iterator)
        .via(RedisFlow.publish[String, String](1, redisClient.connectPubSub().async().getStatefulConnection))
        .runWith(Sink.head[RedisOperationResult[RedisPubSub[String, String], Long]])
      val results = Await.result(receivedMessages, 5.seconds)
      results shouldEqual messages
    }

    "implement get operation" in {

      val (key, value) = ("key1", "val1")

      connection.sync().set(key, value)
      val resultFuture = Source.single(key).via(RedisFlow.get(1, connection)).runWith(Sink.head)
      val result = Await.result(resultFuture, 5.seconds)
      result.result.get shouldEqual value
    }

    "implement mget operation when all values presents" in {

      val sets = Seq[RedisKeyValue[String, String]](RedisKeyValue("key11", "value1"),
                                                    RedisKeyValue("key12", "value2"),
                                                    RedisKeyValue("key13", "value3"))

      Await.result(Source
                     .fromIterator(() => sets.iterator)
                     .grouped(2)
                     .via(RedisFlow.mset(2, connection))
                     .runWith(Sink.ignore),
                   5.seconds)

      val keys = sets.map(_.key)

      val resultFuture = Source.single(keys).via(RedisFlow.mget(1, connection = connection)).runWith(Sink.head)
      val results = Await.result(resultFuture, 5.seconds)
      results.result.get shouldEqual sets

    }

    "implement hset commnand and return true if key does not exists" in {
      val field = Random.alphanumeric.take(10).mkString("")

      val redisHSet = RedisHSet[String, String]("KEY1", field, "value1")
      val hsetResultFuture: Future[RedisOperationResult[RedisHSet[String, String], lang.Boolean]] =
        Source.single(redisHSet).via(RedisFlow.hset(1, connection)).runWith(Sink.head)

      val hsetResult: RedisOperationResult[RedisHSet[String, String], lang.Boolean] =
        Await.result(hsetResultFuture, 5.seconds)

      hsetResult.result.get shouldEqual true
    }

    "implement hset commnand and return false if value exists" in {

      val field = Random.alphanumeric.take(10).mkString("")

      connection.sync().hset("KEY1", field, "value1")
      val redisHSet = RedisHSet[String, String]("KEY1", field, "value1")
      val hsetResultFuture: Future[RedisOperationResult[RedisHSet[String, String], lang.Boolean]] =
        Source.single(redisHSet).via(RedisFlow.hset(1, connection)).runWith(Sink.head)

      val hsetResult: RedisOperationResult[RedisHSet[String, String], lang.Boolean] =
        Await.result(hsetResultFuture, 5.seconds)

      hsetResult.result.get shouldEqual false
    }

    "implement hmset and retrun OK" in {
      val key: String = "KEY2"
      val redisFieldValues = Seq[RedisKeyValue[String, String]](RedisKeyValue("field1", "value1"),
                                                                RedisKeyValue("field2", "value2"),
                                                                RedisKeyValue("field3", "value3"))

      val redisHMSet: RedisHMSet[String, String] = RedisHMSet(key, redisFieldValues)

      val resultAsFuture = Source.single(redisHMSet).via(RedisFlow.hmset(1, connection)).runWith(Sink.head)
      val result = Await.result(resultAsFuture, 5.seconds)
      result.result.get shouldEqual "OK"
    }

    "implement hdel command" in {

      val key: String = "KEY2"

      val redisFieldValues = Seq[RedisKeyValue[String, String]](RedisKeyValue("field133", "value1"),
                                                                RedisKeyValue("field122", "value2"),
                                                                RedisKeyValue("field1233", "value3"))

      val redisHMSet: RedisHMSet[String, String] = RedisHMSet(key, redisFieldValues)

      val hmsetResultAsFuture = Source.single(redisHMSet).via(RedisFlow.hmset(1, connection)).runWith(Sink.head)
      Await.result(hmsetResultAsFuture, 5.seconds)

      val redisHKeyFields = RedisHKeyFields(key, redisFieldValues.map(_.key))

      val resultFuture = Source.single(redisHKeyFields).via(RedisFlow.hdel(1, connection)).runWith(Sink.head)

      val delete = Await.result(resultFuture, 5.seconds)

      delete.result.get shouldEqual 3L

    }

    "check that unsubscribe occurs in subscribeSource when stream completes" in {

      import scala.compat.java8.FutureConverters._
      import scala.collection.JavaConverters._

      val (killSwitch, done) =
        RedisSource
          .subscribe(Seq("topic3", "topic2"), pubSub)
          .viaMat(KillSwitches.single)(Keep.right)
          .grouped(2)
          .toMat(Sink.ignore)(Keep.both)
          .run()

      Thread.sleep(1000)

      killSwitch.shutdown()
      Thread.sleep(1000)
      val channels = Await.result(pubSub.async().pubsubChannels().toScala, 5.second)

      channels.asScala.contains("topic2") shouldEqual false
      channels.asScala.contains("topic3") shouldEqual false

    }

  }
}

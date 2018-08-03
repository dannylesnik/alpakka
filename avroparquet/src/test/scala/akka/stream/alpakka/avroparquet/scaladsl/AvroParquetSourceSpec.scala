/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream.alpakka.avroparquet.scaladsl
import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.scaladsl.TestSink
import org.apache.avro.generic.{GenericRecord, GenericRecordBuilder}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.parquet.avro.{AvroParquetReader, AvroParquetWriter, AvroReadSupport}
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.hadoop.{ParquetReader, ParquetWriter}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

class AvroParquetSourceSpec extends Specification with AbstractAvroParquet with AfterAll with BeforeAll {

  "AvroParquetSource" should {

    "read from parquet file" in {

      //#init-source
      val file = folder + "/test.parquet"
      val conf = new Configuration()
      conf.setBoolean(AvroReadSupport.AVRO_COMPATIBILITY, true)
      val reader: ParquetReader[GenericRecord] =
        AvroParquetReader.builder[GenericRecord](HadoopInputFile.fromPath(new Path(file), conf)).withConf(conf).build()

      val source: Source[GenericRecord, NotUsed] = AvroParquetSource(reader)
      //#init-source

      val (_, sink) = source.toMat(TestSink.probe)(Keep.both).run()

      sink.toStrict(Duration(3, TimeUnit.SECONDS)).seq.length shouldEqual 3

    }

  }

  override def beforeAll(): Unit = {
    case class Document(id: String, body: String)

    val docs = List[Document](Document("id1", "data1"), Document("id1", "data2"), Document("id3", " data3"))

    val source: Source[Document, NotUsed] = akka.stream.scaladsl.Source.fromIterator(() => docs.iterator)

    val file = folder + "/test.parquet"
    val conf = new Configuration()
    conf.setBoolean(AvroReadSupport.AVRO_COMPATIBILITY, true)
    val writer: ParquetWriter[GenericRecord] =
      AvroParquetWriter.builder[GenericRecord](new Path(file)).withConf(conf).withSchema(schema).build()
    val sink = AvroParquetSink(writer)

    val result: Future[Done] = source
      .map { doc =>
        new GenericRecordBuilder(schema)
          .set("id", doc.id)
          .set("body", doc.body)
          .build()
      }
      .runWith(sink)
    Await.result[Done](result, Duration(5, TimeUnit.SECONDS))
  }
}

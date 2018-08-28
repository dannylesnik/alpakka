/*
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.javadsl;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.FlowShape;
import akka.stream.alpakka.avroparquet.javadsl.AvroParquetFlow;
import akka.stream.alpakka.avroparquet.javadsl.AvroParquetSource;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.util.HadoopInputFile;

import java.io.IOException;

public class Examples {

  private final Schema schema =
      new Schema.Parser()
          .parse(
              "{\"type\":\"record\",\"name\":\"Document\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"body\",\"type\":\"string\"}]}");
  // #init-system
  ActorSystem system = ActorSystem.create();
  // #init-system
  ActorMaterializer materializer = ActorMaterializer.create(system);
  // #init-source
  Configuration conf = new Configuration();

  // #init-reader
  ParquetReader<GenericRecord> reader =
      AvroParquetReader.<GenericRecord>builder(
              HadoopInputFile.fromPath(new Path("./test.parquet"), conf))
          .disableCompatibility()
          .build();
  // #init-reader

  // #init-source
  Source<GenericRecord, NotUsed> source = AvroParquetSource.create(reader);

  public Examples() throws IOException {

    // #init-flow
    ParquetWriter<GenericRecord> writer =
        AvroParquetWriter.<GenericRecord>builder(new Path("./test.parquet"))
            .withConf(conf)
            .withSchema(schema)
            .build();

    Flow<GenericRecord, GenericRecord, NotUsed> flow = AvroParquetFlow.create(writer);

    source.via(flow).runWith(Sink.ignore(), materializer);
    // #init-flow

  }
}

/*
 *  Copyright (c) 2016 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.uber.hoodie.common.util;

import com.uber.hoodie.avro.MercifulJsonConverter;
import com.uber.hoodie.common.model.HoodieAvroPayload;
import com.uber.hoodie.common.model.HoodieKey;
import com.uber.hoodie.common.model.HoodieRecord;
import com.uber.hoodie.common.table.timeline.HoodieActiveTimeline;
import com.uber.hoodie.exception.HoodieIOException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.io.DecoderFactory;

public class SchemaTestUtil {

  public static Schema getSimpleSchema() throws IOException {
    return new Schema.Parser().parse(SchemaTestUtil.class.getResourceAsStream("/simple-test.avro"));
  }

  public static List<IndexedRecord> generateTestRecords(int from, int limit) throws IOException, URISyntaxException {
    return toRecords(getSimpleSchema(), getSimpleSchema(), from, limit);
  }

  private static List<IndexedRecord> toRecords(Schema writerSchema, Schema readerSchema, int from, int limit)
      throws IOException, URISyntaxException {
    GenericDatumReader<IndexedRecord> reader = new GenericDatumReader<>(writerSchema, readerSchema);
    // Required to register the necessary JAR:// file system
    URI resource = SchemaTestUtil.class.getClass().getResource("/sample.data").toURI();
    Path dataPath;
    if (resource.toString().contains("!")) {
      dataPath = uriToPath(resource);
    } else {
      dataPath = Paths.get(SchemaTestUtil.class.getClass().getResource("/sample.data").toURI());
    }

    try (Stream<String> stream = Files.lines(dataPath)) {
      return stream.skip(from).limit(limit).map(s -> {
        try {
          return reader.read(null, DecoderFactory.get().jsonDecoder(writerSchema, s));
        } catch (IOException e) {
          throw new HoodieIOException("Could not read data from simple_data.json", e);
        }
      }).collect(Collectors.toList());
    } catch (IOException e) {
      throw new HoodieIOException("Could not read data from simple_data.json", e);
    }
  }

  static Path uriToPath(URI uri) throws IOException {
    final Map<String, String> env = new HashMap<>();
    final String[] array = uri.toString().split("!");
    FileSystem fs;
    try {
      fs = FileSystems.getFileSystem(URI.create(array[0]));
    } catch (FileSystemNotFoundException e) {
      fs = FileSystems.newFileSystem(URI.create(array[0]), env);
    }
    return fs.getPath(array[1]);
  }

  public static List<IndexedRecord> generateHoodieTestRecords(int from, int limit)
      throws IOException, URISyntaxException {
    List<IndexedRecord> records = generateTestRecords(from, limit);
    String commitTime = HoodieActiveTimeline.createNewCommitTime();
    Schema hoodieFieldsSchema = HoodieAvroUtils.addMetadataFields(getSimpleSchema());
    return records.stream().map(s -> HoodieAvroUtils.rewriteRecord((GenericRecord) s, hoodieFieldsSchema)).map(p -> {
      p.put(HoodieRecord.RECORD_KEY_METADATA_FIELD, UUID.randomUUID().toString());
      p.put(HoodieRecord.PARTITION_PATH_METADATA_FIELD, "0000/00/00");
      p.put(HoodieRecord.COMMIT_TIME_METADATA_FIELD, commitTime);
      return p;
    }).collect(Collectors.toList());

  }

  public static List<HoodieRecord> generateHoodieTestRecords(int from, int limit, Schema schema)
      throws IOException, URISyntaxException {
    List<IndexedRecord> records = generateTestRecords(from, limit);
    return records.stream()
        .map(s -> HoodieAvroUtils.rewriteRecord((GenericRecord) s, schema))
        .map(p -> convertToHoodieRecords(p,
            UUID.randomUUID().toString(), "000/00/00")).collect(
            Collectors.toList());
  }

  private static HoodieRecord convertToHoodieRecords(IndexedRecord iRecord, String key, String partitionPath) {
    return new HoodieRecord<>(new HoodieKey(key, partitionPath),
        new HoodieAvroPayload(Optional.of((GenericRecord) iRecord)));
  }

  public static List<IndexedRecord> updateHoodieTestRecords(List<String> oldRecordKeys, List<IndexedRecord> newRecords,
      String commitTime) throws IOException, URISyntaxException {

    return newRecords.stream()
        .map(p -> {
          ((GenericRecord) p).put(HoodieRecord.RECORD_KEY_METADATA_FIELD, oldRecordKeys.remove(0));
          ((GenericRecord) p).put(HoodieRecord.PARTITION_PATH_METADATA_FIELD, "0000/00/00");
          ((GenericRecord) p).put(HoodieRecord.COMMIT_TIME_METADATA_FIELD, commitTime);
          return p;
        }).collect(
            Collectors.toList());

  }

  public static List<HoodieRecord> generateHoodieTestRecordsWithoutHoodieMetadata(int from, int limit)
      throws IOException, URISyntaxException {

    List<IndexedRecord> iRecords = generateTestRecords(from, limit);
    return iRecords
        .stream()
        .map(r -> new HoodieRecord<>(new HoodieKey(UUID.randomUUID().toString(), "0000/00/00"),
            new HoodieAvroPayload(Optional.of((GenericRecord) r)))).collect(Collectors.toList());
  }

  public static List<HoodieRecord> updateHoodieTestRecordsWithoutHoodieMetadata(List<HoodieRecord> oldRecords,
      Schema schema,
      String fieldNameToUpdate, String newValue)
      throws IOException, URISyntaxException {
    return oldRecords
        .stream()
        .map(r -> {
          try {
            GenericRecord rec = (GenericRecord) r.getData().getInsertValue(schema).get();
            rec.put(fieldNameToUpdate, newValue);
            return new HoodieRecord<>(r.getKey(),
                new HoodieAvroPayload(Optional.of(rec)));
          } catch (IOException io) {
            throw new HoodieIOException("unable to get data from hoodie record", io);
          }
        }).collect(Collectors.toList());
  }

  public static Schema getEvolvedSchema() throws IOException {
    return new Schema.Parser().parse(SchemaTestUtil.class.getResourceAsStream("/simple-test-evolved.avro"));
  }

  public static List<IndexedRecord> generateEvolvedTestRecords(int from, int limit)
      throws IOException, URISyntaxException {
    return toRecords(getSimpleSchema(), getEvolvedSchema(), from, limit);
  }

  public static Schema getComplexEvolvedSchema() throws IOException {
    return new Schema.Parser().parse(SchemaTestUtil.class.getResourceAsStream("/complex-test-evolved.avro"));
  }

  public static GenericRecord generateAvroRecordFromJson(Schema schema, int recordNumber, String commitTime,
      String fileId) throws IOException {
    TestRecord record = new TestRecord(commitTime, recordNumber, fileId);
    MercifulJsonConverter converter = new MercifulJsonConverter(schema);
    return converter.convert(record.toJsonString());
  }
}
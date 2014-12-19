/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.stage.source.kafka;

import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.lib.csv.OverrunCsvParser;
import com.streamsets.pipeline.lib.io.CountingReader;
import com.streamsets.pipeline.lib.json.OverrunStreamingJsonParser;
import com.streamsets.pipeline.lib.json.StreamingJsonParser;
import com.streamsets.pipeline.lib.stage.source.spooldir.csv.CvsFileModeChooserValues;
import com.streamsets.pipeline.lib.stage.source.spooldir.json.JsonFileModeChooserValues;
import com.streamsets.pipeline.lib.stage.source.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@GenerateResourceBundle
@StageDef(version="0.0.1",
  label="Kafka Source")
public class KafkaSource extends BaseSource {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaSource.class);

  /****************** Start config options *******************/

  @ConfigDef(required = true,
    type = ConfigDef.Type.STRING,
    label = "Topic",
    defaultValue = "mytopic")
  public String topic;

  //TODO: In v1 of this source [not the v1 of SDC] we expect the user to specify the partition
  //I am currently exploring ways to identify all given partitions of the topic and the related brokers
  //so that messages can be extracted from all of the partitions.
  //This keeping in mind the load balancing use case where people may write to different partitions just to load balance
  //and better make use of resources
  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    label = "Partition",
    defaultValue = "0")
  public int partition;

  @ConfigDef(required = true,
    type = ConfigDef.Type.STRING,
    description = "A known kafka broker. Does not have to be the leader of the partition",
    label = "Broker Host",
    defaultValue = "localhost")
  public String brokerHost;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    label = "Broker Port",
    defaultValue = "9092")
  public int brokerPort;

  @ConfigDef(required = true,
    type = ConfigDef.Type.BOOLEAN,
    label = "From Beginning",
    defaultValue = "false")
  public boolean fromBeginning;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    description = "The maximum data per batch. The source uses this size when making a fetch request from kafka",
    label = "Max Fetch Size",
    defaultValue = "64000")
  public int maxBatchSize;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    description = "The maximum wait time in seconds before the kafka fetch request returns if no message is available.",
    label = "Max Wait Time",
    defaultValue = "1000")
  public int maxWaitTime;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    description = "The minimum data per batch. The source uses this size when making a fetch request from kafka",
    label = "Min Fetch Size",
    defaultValue = "8000")
  public int minBatchSize;

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    label = "Payload Type")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = PayloadTypeChooserValues.class)
  public PayloadType payloadType;

  /********  For Json Content  ***********/

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    label = "JSON Content",
    description = "Indicates if the JSON files have a single JSON array object or multiple JSON objects",
    defaultValue = "ARRAY_OBJECTS")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = JsonFileModeChooserValues.class)
  public StreamingJsonParser.Mode jsonContent;

  @ConfigDef(required = true,
    type = ConfigDef.Type.INTEGER,
    label = "Maximum JSON Object Length",
    description = "The maximum length for a JSON Object being converted to a record, if greater the full JSON " +
      "object is discarded and processing continues with the next JSON object",
    defaultValue = "4096")
  public int maxJsonObjectLen;

  /********  For CSV Content  ***********/

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    label = "CSV Format",
    description = "The specific CSV format of the files",
    defaultValue = "DEFAULT")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = CvsFileModeChooserValues.class)
  public String csvFileFormat;

  /****************** End config options *******************/

  private KafkaConsumer kafkaConsumer;

  @Override
  public void init() {
    kafkaConsumer = new KafkaConsumer(topic, partition, new KafkaBroker(brokerHost, brokerPort), minBatchSize,
      maxBatchSize, maxWaitTime);
    kafkaConsumer.init();
  }

  @Override
  public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
    long offsetToRead;
    if(lastSourceOffset == null || lastSourceOffset.isEmpty()) {
      offsetToRead = kafkaConsumer.getOffsetToRead(fromBeginning);
    } else {
      offsetToRead = Long.parseLong(lastSourceOffset);
    }

    List<MessageAndOffset> partitionToPayloadMaps = new ArrayList<>();
    try {
      partitionToPayloadMaps.addAll(kafkaConsumer.read(offsetToRead));
    } catch (SocketTimeoutException e) {
      //If the value of consumer.timeout.ms is set to a positive integer, a timeout exception is thrown to the
      //consumer if no message is available for consumption after the specified timeout value.
      //If this happens exit gracefully
      LOG.warn("SocketTimeoutException encountered while fetching message from Kafka.");
    } catch (StageException e) {
      throw e;
    } catch (Exception e) {
      throw new StageException(null, e.getMessage(), e);
    }

    String offsetToReturn = null;
    int recordCounter = 0;
    for(MessageAndOffset partitionToPayloadMap : partitionToPayloadMaps) {
      //create record by parsing the message payload based on the pay load type configuration
      //As of now handle just String
      if(recordCounter == maxBatchSize) {
        //even though kafka has many messages, we need to cap the number of records to a value indicated by maxBatchSize.
        //return the offset of the previous record so that the next time we get start from this message which did not
        //make it to this batch.
        break;
      }
      recordCounter++;
      Record record = getContext().createRecord(topic + "." + partition + "." + System.currentTimeMillis() + "."
        + recordCounter);
      ByteBuffer payload  = partitionToPayloadMap.getPayload();
      byte[] bytes = new byte[payload.limit()];
      payload.get(bytes);

      offsetToReturn = String.valueOf(partitionToPayloadMap.getOffset());

      if (payloadType == PayloadType.STRING) {
        //TODO: Is the last message complete?
        record.set(Field.create(new String(bytes)));
      } else if (payloadType == PayloadType.CSV) {
        try (CountingReader reader =
               new CountingReader(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes))))) {
          OverrunCsvParser parser = new OverrunCsvParser(reader, CvsFileModeChooserValues.getCSVFormat(csvFileFormat));
          String[] columns = parser.read();
          Map<String, Field> map = new LinkedHashMap<>();
          List<Field> values = new ArrayList<>(columns.length);
          for (String column : columns) {
            values.add(Field.create(column));
          }
          map.put("values", Field.create(values));
          record.set(Field.create(map));
        }catch (Exception e) {
          throw new StageException(null, e.getMessage(), e);
        }
      } else if(payloadType == PayloadType.JSON) {
        try (CountingReader reader =
               new CountingReader(new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes))))) {
          OverrunStreamingJsonParser parser = new OverrunStreamingJsonParser(reader, jsonContent, maxJsonObjectLen);
          record.set(JsonUtil.jsonToField(parser.read()));
        } catch (Exception e) {
          throw new StageException(null, e.getMessage(), e);
        }
      } else {
        //This can happen only due to coding error
        throw new IllegalStateException("Unexpected state");
      }
      batchMaker.addRecord(record);
    }
    return offsetToReturn;
  }

  @Override
  public void destroy() {
    kafkaConsumer.destroy();
  }

}
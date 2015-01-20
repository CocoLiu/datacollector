/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.lib.kafka;

import com.streamsets.pipeline.api.Batch;
import com.streamsets.pipeline.api.ChooserMode;
import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.ValueChooser;
import com.streamsets.pipeline.api.base.BaseTarget;
import com.streamsets.pipeline.el.ELBasicSupport;
import com.streamsets.pipeline.el.ELEvaluator;
import com.streamsets.pipeline.el.ELRecordSupport;
import com.streamsets.pipeline.el.ELStringSupport;
import com.streamsets.pipeline.el.ELUtils;
import com.streamsets.pipeline.lib.util.CsvUtil;
import com.streamsets.pipeline.lib.util.JsonUtil;
import com.streamsets.pipeline.lib.util.KafkaStageLibError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.jsp.el.ELException;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@GenerateResourceBundle
@StageDef(version="0.0.1",
  label="Kafka Target",
  icon="kafka.png")
public class KafkaTarget extends BaseTarget {

  private static final Logger LOG = LoggerFactory.getLogger(KafkaTarget.class);

  @ConfigDef(required = true,
    type = ConfigDef.Type.STRING,
    description = "The Kafka topic from which the messages must be read",
    label = "Topic",
    defaultValue = "topicName")
  public String topic;

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    description = "Indicates the strategy to select a partition while writing a message." +
      "This option is activated only if a negative integer is supplied as the value for partition.",
    label = "Partition Strategy",
    defaultValue = "ROUND_ROBIN")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = PartitionStrategyChooserValues.class)
  public PartitionStrategy partitionStrategy;

  @ConfigDef(required = false,
    type = ConfigDef.Type.STRING,
    description = "Expression that determines the partition of Kafka topic to which the messages must be written",
    label = "Partition Expression",
    defaultValue = "0",
    dependsOn = "partitionStrategy",
    triggeredByValue = {"EXPRESSION"})
  public String partition;

  @ConfigDef(required = true,
    type = ConfigDef.Type.MAP,
    label = "Constants for expressions",
    description = "Defines constant values available to all expressions",
    dependsOn = "partitionStrategy",
    triggeredByValue = {"EXPRESSION"})
  public Map<String, ?> constants;

  @ConfigDef(required = true,
    type = ConfigDef.Type.STRING,
    description = "This is for bootstrapping and the producer will only use it for getting metadata " +
      "(topics, partitions and replicas). The socket connections for sending the actual data will be established " +
      "based on the broker information returned in the metadata. The format is host1:port1,host2:port2, and the list " +
      "can be a subset of brokers or a VIP pointing to a subset of brokers. " +
      "Please specify more than one broker information if known.",
    label = "Metadata Broker List",
    defaultValue = "localhost:9092")
  public String metadataBrokerList;

  @ConfigDef(required = true,
    type = ConfigDef.Type.MODEL,
    description = "Type of data sent as kafka message payload",
    label = "Payload Type",
    defaultValue = "LOG")
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = PayloadTypeChooserValues.class)
  public PayloadType payloadType;

  @ConfigDef(required = false,
    type = ConfigDef.Type.MAP,
    description = "Additional configuration properties which will be used by the underlying Kafka Producer. " +
     "The following options, if specified, are ignored : \"metadata.broker.list\", \"producer.type\", " +
      "\"key.serializer.class\", \"partitioner.class\", \"serializer.class\".",
    defaultValue = "",
    label = "Kafka Producer Configuration Properties")
  public Map<String, String> kafkaProducerConfigs;

  /********  For CSV Content  ***********/

  @ConfigDef(required = false,
    type = ConfigDef.Type.MODEL,
    label = "CSV Format",
    description = "The specific CSV format of the files",
    defaultValue = "CSV",
    dependsOn = "payloadType", triggeredByValue = {"CSV"})
  @ValueChooser(type = ChooserMode.PROVIDED, chooserValues = CvsFileModeChooserValues.class)
  public CsvFileMode csvFileFormat;

  private KafkaProducer kafkaProducer;
  private long recordCounter = 0;
  private ELEvaluator elEvaluator;
  private ELEvaluator.Variables variables;

  @Override
  public void init() throws StageException {
    kafkaProducer = new KafkaProducer(topic, metadataBrokerList,
      payloadType, partitionStrategy, kafkaProducerConfigs);
    kafkaProducer.init();

    if(partitionStrategy == PartitionStrategy.EXPRESSION) {
      variables = ELUtils.parseConstants(constants);
      elEvaluator = new ELEvaluator();
      ELBasicSupport.registerBasicFunctions(elEvaluator);
      ELRecordSupport.registerRecordFunctions(elEvaluator);
      ELStringSupport.registerStringFunctions(elEvaluator);
      validateExpressions();
    }
  }

  private void validateExpressions() throws StageException {
    Record record = new Record(){
      @Override
      public Header getHeader() {
        return null;
      }

      @Override
      public Field get() {
        return null;
      }

      @Override
      public Field set(Field field) {
        return null;
      }

      @Override
      public Field get(String fieldPath) {
        return null;
      }

      @Override
      public Field delete(String fieldPath) {
        return null;
      }

      @Override
      public boolean has(String fieldPath) {
        return false;
      }

      @Override
      public Set<String> getFieldPaths() {
        return null;
      }

      @Override
      public Field set(String fieldPath, Field newField) {
        return null;
      }
    };

    ELRecordSupport.setRecordInContext(variables, record);
    partition = "${" + partition + "}";
    try {
      elEvaluator.eval(variables, partition);
    } catch (ELException ex) {
      LOG.error(KafkaStageLibError.LIB_0357.getMessage(), partition, ex.getMessage());
      throw new StageException(KafkaStageLibError.LIB_0357, partition, ex.getMessage(), ex);
    }
  }

  @Override
  public void write(Batch batch) throws StageException {
    long batchRecordCounter = 0;
    Iterator<Record> records = batch.getRecords();
    if(records.hasNext()) {
      while (records.hasNext()) {
        Record r = records.next();

        String partitionKey = getPartitionKey(r);
        if(partitionKey == null) {
          //record in error
          continue;
        }
        if(!partitionKey.isEmpty() && !validatePartition(r, partitionKey)) {
          continue;
        }
        try {
          kafkaProducer.enqueueMessage(serializeRecord(r), partitionKey);
        } catch (IOException e) {
          throw new StageException(KafkaStageLibError.LIB_0351, e.getMessage(), e);
        }
        batchRecordCounter++;
      }

      kafkaProducer.write();
      recordCounter += batchRecordCounter;
    }
  }

  private boolean validatePartition(Record r, String partitionKey) {
    int partition = -1;
    try {
      partition = Integer.parseInt(partitionKey);
    } catch (NumberFormatException e) {
      LOG.warn(KafkaStageLibError.LIB_0355.getMessage(), partitionKey, topic, e.getMessage());
      getContext().toError(r, KafkaStageLibError.LIB_0355, partitionKey, topic, e.getMessage(), e);
      return false;
    }
    //partition number is an integer starting from 0 ... n-1, where n is the number of partitions for topic t
    if(partition < 0 || partition >= kafkaProducer.getNumberOfPartitions()) {
      LOG.warn(KafkaStageLibError.LIB_0356.getMessage(), partition, topic, kafkaProducer.getNumberOfPartitions());
      getContext().toError(r, KafkaStageLibError.LIB_0356, partition, topic, kafkaProducer.getNumberOfPartitions());
      return false;
    }
    return true;
  }

  private String getPartitionKey(Record record) throws StageException {
    if(partitionStrategy == PartitionStrategy.EXPRESSION) {
      ELRecordSupport.setRecordInContext(variables, record);
      Object result;
      try {
        result = elEvaluator.eval(variables, partition);
      } catch (ELException e) {
        LOG.warn(KafkaStageLibError.LIB_0354.getMessage(), partition, record.getHeader().getSourceId(), e.getMessage());
        getContext().toError(record, KafkaStageLibError.LIB_0354, partition, record.getHeader().getSourceId(),
          e.getMessage(), e);
        return null;
      }
      return result.toString();
    }
    return "";
  }

  @Override
  public void destroy() {
    LOG.info("Wrote {} number of records to Kafka Broker", recordCounter);
    if(kafkaProducer != null) {
      kafkaProducer.destroy();
    }
  }

  private byte[] serializeRecord(Record r) throws IOException {
    if(payloadType == PayloadType.LOG) {
      return r.get().getValue().toString().getBytes();
    } if (payloadType == PayloadType.JSON) {
      return JsonUtil.jsonRecordToString(r).getBytes();
    } if (payloadType == PayloadType.CSV) {
      return CsvUtil.csvRecordToString(r, csvFileFormat.getFormat()).getBytes();
    }
    return null;
  }
}